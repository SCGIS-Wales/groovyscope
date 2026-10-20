"""``groovyscope checkup``: everything a user would otherwise do by hand after an install or upgrade, as one report
with the exact fix per item, and the safe repairs applied on request. The Kiro agent's start hook runs it with
``--agent``, so a session opens with the setup already repaired and only the decisions left to the user.

Pure report building with injected collaborators; nothing here prints, exits or raises past ``build``: a check that
fails is reported as an item, never as a crash of the agent start.
"""

from __future__ import annotations

import json
from collections.abc import Callable, Mapping
from pathlib import Path
from typing import Any, NotRequired, TypedDict

from groovyscope import __version__, benchmark, daemon, doctor, kiro, kiro_agent, profiles, sweep
from groovyscope import classpath as classpath_state
from groovyscope import config as config_module
from groovyscope.classpath import DEFAULT_FILE
from groovyscope.config import load_config

MAX_MESSAGE = 220
# How each classpath state shows in the report: stale is information because the engine still answers, with
# library classes added since the export unresolved until the next `groovyscope classpath`.
CLASSPATH_LEVELS = {"fresh": "ok", "configured": "ok", "stale": "info", "missing": "warn", "none": "info"}


class Item(TypedDict):
    """One checked thing: its level (ok, info, warn, fail), what was found, and the fix when one exists."""

    level: str
    name: str
    message: str
    fix: NotRequired[str]  # the exact command
    safe: NotRequired[bool]  # True: checkup may run it itself; False: the user decides (runs a build, grants trust)


class Report(TypedDict):
    """The checkup: items, what was repaired in this run, and what still needs the user."""

    version: str
    root: str
    items: list[Item]
    repaired: list[str]
    todo: list[str]


Check = Callable[[], tuple[Item, list[str]]]  # returns the item and what it repaired


def profile_guidance(
    root: Path, config_path: Path | None, environ: Mapping[str, str], home: Path, *, global_scope: bool
) -> str:
    """Steering text from the active runtime profiles (for the Kiro home: only profiles kept outside this
    repository, since the global steering applies everywhere); empty when none or the config is invalid."""
    try:
        effective = load_config(root, config_path, dict(environ))
    except (ValueError, OSError):
        return ""
    found = profiles.discover(root, effective.get("profiles", {}), home)
    if global_scope:
        resolved = root.resolve()
        outside = [p for p in found["profiles"] if not Path(p["path"]).resolve().is_relative_to(resolved)]
        return profiles.guidance({**found, "profiles": outside}, None)
    return profiles.guidance(found, root)


IDLE_NOTE = (
    "groovyscope: not a Groovy project here (no build file, Jenkinsfile, .groovyscope.yaml or Groovy sources); "
    "no checks or repairs ran"
)


def is_project(root: Path) -> bool:
    """A Groovy project: a build file, a configuration, a Jenkinsfile, or Groovy sources in the usual roots."""
    if any((root / marker).exists() for marker in doctor.PROJECT_MARKERS):
        return True
    return any((root / folder).is_dir() for folder in ("src/main/groovy", "src/test/groovy", "vars"))


def has_lsp_entry(root: Path) -> bool:
    try:
        document: Any = json.loads((root / ".kiro" / "settings" / "lsp.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return False
    languages = document.get("languages") if isinstance(document, dict) else None
    return isinstance(languages, dict) and "groovy" in languages


def kiro_files_item(
    root: Path,
    config_path: Path | None,
    environ: Mapping[str, str],
    home: Path,
    *,
    fix: bool,
    run: kiro_agent.KiroRunner | None,
) -> tuple[Item, list[str]]:
    """The global Kiro files: absent, written by an older groovyscope, or hiding MCP servers from the agent."""
    kiro_home = kiro.kiro_home(environ, home)
    present = kiro.has_global_setup(kiro_home)
    written = kiro.kiro_files_version(kiro_home) if present else None
    hidden = doctor.agent_hidden_servers(kiro_home / "agents" / "groovy.json", root, kiro_home) if present else []
    if present and written == __version__ and not hidden:
        return ({"level": "ok", "name": "kiro files", "message": f"{kiro_home}, written by {__version__}"}, [])
    if not present:
        reason = "no global Kiro setup"
        command = "groovyscope kiro init --global"
    elif hidden:
        reason = f"the agent's tools list hides these MCP servers: {', '.join(hidden)}"
        command = "groovyscope kiro repair --global"
    else:
        reason = f"written by groovyscope {written or 'an older release'}, package is {__version__}"
        command = "groovyscope kiro repair --global"
    if not fix:
        return ({"level": "warn", "name": "kiro files", "message": reason, "fix": command, "safe": True}, [])
    guidance = profile_guidance(root, config_path, environ, home, global_scope=True)
    changes = kiro.plan_changes(kiro_home, global_scope=True, profile_guidance=guidance, run=run)
    written_paths = kiro.apply_changes(kiro_home, changes)
    what = "installed" if not present else "refreshed"
    message = f"{what} in {kiro_home} ({len(written_paths)} file(s), backups kept)"
    notes = [change["note"] for change in changes if change.get("note")]
    if notes:
        message += "; " + "; ".join(notes)
    return ({"level": "ok", "name": "kiro files", "message": message}, [f"Kiro files {message}"])


def project_item(root: Path, *, fix: bool) -> tuple[Item, list[str]]:
    """The project's LSP entry, which Kiro reads only from the project's own .kiro folder."""
    if not is_project(root):
        return ({"level": "info", "name": "project", "message": "not a Groovy project folder; nothing to set up"}, [])
    if has_lsp_entry(root):
        return ({"level": "ok", "name": "project", "message": "LSP entry present in .kiro/settings/lsp.json"}, [])
    if not fix:
        item: Item = {
            "level": "warn",
            "name": "project",
            "message": "no groovy entry in .kiro/settings/lsp.json, so Kiro's code tool cannot use groovyscope here",
            "fix": "groovyscope kiro init",
            "safe": True,
        }
        return (item, [])
    changes = kiro.plan_changes(root, lsp_only=True)
    kiro.apply_changes(root, changes)
    return (
        {"level": "ok", "name": "project", "message": "LSP entry written to .kiro/settings/lsp.json"},
        ["project LSP entry written (.kiro/settings/lsp.json); restart Kiro CLI for /code to pick it up"],
    )


def workspace_items(root: Path, config_path: Path | None, environ: Mapping[str, str]) -> list[Item]:
    """Trust and classpath: the two decisions only the user can take."""
    if not is_project(root):
        return []
    try:
        effective = load_config(root, config_path, dict(environ))
    except (ValueError, OSError) as exc:
        return [{"level": "fail", "name": "config", "message": str(exc).replace("\n", " ")[:MAX_MESSAGE]}]
    items: list[Item] = []
    trusted = bool(effective["workspace"].get("trust"))
    if trusted:
        items.append({"level": "ok", "name": "trust", "message": "trusted workspace"})
    else:
        items.append(
            {
                "level": "info",
                "name": "trust",
                "message": "untrusted workspace: its .groovyscope.yaml cannot pick the JDK or rule sets, and "
                "`groovyscope classpath` (which runs the build) is refused",
                "fix": "groovyscope trust",
                "safe": False,
            }
        )
    classpath_file = root / Path(effective["workspace"].get("classpathFile") or DEFAULT_FILE)
    configured = bool(effective["workspace"].get("classpath"))
    state = classpath_state.status(root, classpath_file, configured=configured)
    item: Item = {"level": CLASSPATH_LEVELS[state["state"]], "name": "classpath", "message": state["message"]}
    if state.get("fix"):
        if not trusted:
            item["message"] += "; needs `groovyscope trust` first"
        item["fix"] = state["fix"]
        item["safe"] = False
    items.append(item)
    return items


def benchmark_item(environ: Mapping[str, str], home: Path, *, fix: bool) -> tuple[Item, list[str]]:
    target = benchmark.corpus_root(home, environ)
    if not fix:
        found = benchmark.status(target)
        bundled = benchmark.manifest()
        if found["present"] and bundled is not None and found["version"] == bundled["version"]:
            return ({"level": "ok", "name": "benchmark", "message": f"corpus at {target}"}, [])
        item: Item = {
            "level": "info",
            "name": "benchmark",
            "message": "benchmark corpus not unpacked yet"
            if not found["present"]
            else "benchmark corpus is older than the bundled one",
            "fix": "groovyscope benchmark path",
            "safe": True,
        }
        return (item, [])
    result = benchmark.ensure(target)
    if result is None:
        return ({"level": "info", "name": "benchmark", "message": "this package bundles no corpus"}, [])
    if result["action"] == "up-to-date":
        return ({"level": "ok", "name": "benchmark", "message": f"corpus at {target}"}, [])
    return (
        {"level": "ok", "name": "benchmark", "message": f"corpus {result['action']} at {target}"},
        [f"benchmark corpus {result['action']} at {target} (CodeNarc {result['version']}, {result['files']} files)"],
    )


def daemon_item(root: Path, environ: Mapping[str, str], config_hash: str | None) -> Item:
    info = daemon.info(root, environ)
    if not info["running"]:
        if info.get("outOfMemory"):
            return {"level": "warn", "name": "daemon", "message": daemon.out_of_memory_advice(str(info.get("log", "")))}
        return {"level": "info", "name": "daemon", "message": "not running; starts with the first request"}
    status = info.get("status") or {}
    warning = daemon.memory_warning(status if isinstance(status, Mapping) else None)
    if warning:
        return {"level": "warn", "name": "daemon", "message": warning}
    running = status.get("configHash") if isinstance(status, Mapping) else None
    if config_hash and isinstance(running, str) and running and running != config_hash:
        return {
            "level": "info",
            "name": "daemon",
            "message": "running with an older configuration; the next request reloads it in place",
        }
    grown = info.get("heap")
    if grown:
        return {
            "level": "info",
            "name": "daemon",
            "message": f"running (engine {status.get('engine', '?')}) with a {grown} heap, grown after an "
            "out-of-memory exit; raise daemon.heap to keep it",
        }
    return {"level": "ok", "name": "daemon", "message": f"running (engine {status.get('engine', '?')})"}


def cache_item(environ: Mapping[str, str], logs_kept: int, *, fix: bool) -> tuple[Item, list[str]]:
    """Rotated logs beyond the kept count, stale CDS archives and old heap dumps; removed with ``fix``."""
    items = sweep.inventory(daemon.cache_dir(environ), logs_kept=logs_kept)
    if not items:
        return ({"level": "ok", "name": "cache", "message": "nothing to sweep"}, [])
    if fix:
        failed = sweep.remove(items)
        done = [f"removed {item['path']}" for item in items if item["path"] not in failed]
        if failed:
            return ({"level": "warn", "name": "cache", "message": f"{len(failed)} file(s) could not be removed"}, done)
        return ({"level": "ok", "name": "cache", "message": f"swept {sweep.summary(items)}"}, done)
    item: Item = {"level": "info", "name": "cache", "message": sweep.summary(items), "fix": "groovyscope checkup --fix"}
    return (item, [])


def guarded(name: str, check: Check) -> tuple[Item, list[str]]:
    """Run one check; a crash becomes an item, never an exception out of the checkup."""
    try:
        return check()
    except Exception as exc:
        return ({"level": "warn", "name": name, "message": f"could not check: {exc}"[:MAX_MESSAGE]}, [])


def build(
    root: Path,
    config_path: Path | None,
    environ: Mapping[str, str],
    home: Path,
    *,
    fix: bool,
    run: kiro_agent.KiroRunner | None = None,
    config_hash: str | None = None,
) -> Report:
    """The checkup. With ``fix``, the safe repairs (Kiro files, project LSP entry, benchmark corpus) are applied."""
    items: list[Item] = []
    repaired: list[str] = []

    def add(item: Item, done: list[str]) -> None:
        items.append(item)
        repaired.extend(done)

    add(*guarded("kiro files", lambda: kiro_files_item(root, config_path, environ, home, fix=fix, run=run)))
    add(*guarded("project", lambda: project_item(root, fix=fix)))
    try:
        items.extend(workspace_items(root, config_path, environ))
    except Exception as exc:
        items.append({"level": "warn", "name": "workspace", "message": f"could not check: {exc}"[:MAX_MESSAGE]})
    add(*guarded("benchmark", lambda: benchmark_item(environ, home, fix=fix)))
    add(*guarded("agent", lambda: (agent_item(root, environ, home, run), [])))
    add(*guarded("jdk", lambda: (jdk_item(root, config_path, environ), [])))
    add(*guarded("engine jar", lambda: (jar_item(environ), [])))
    add(*guarded("daemon", lambda: (daemon_item(root, environ, config_hash), [])))
    add(*guarded("cache", lambda: cache_item(environ, logs_kept(root, config_path, environ), fix=fix)))
    todo = [f"{item['message']}. Fix: `{item['fix']}`" for item in items if item.get("fix") and not item.get("safe")]
    todo += [f"{item['message']}. Fix: `{item['fix']}`" for item in items if item.get("fix") and item.get("safe")]
    todo += [f"{item['name']}: {item['message']}" for item in items if item["level"] == "fail" and not item.get("fix")]
    return {"version": __version__, "root": str(root), "items": items, "repaired": repaired, "todo": todo}


def logs_kept(root: Path, config_path: Path | None, environ: Mapping[str, str]) -> int:
    """``daemon.logsKept`` from the effective configuration, 3 when it cannot be read."""
    try:
        effective = load_config(root, config_path, environ)
    except Exception:
        return 3
    return int((effective.get("daemon") or {}).get("logsKept", config_module.default("daemon", "logsKept")))


def agent_item(root: Path, environ: Mapping[str, str], home: Path, run: kiro_agent.KiroRunner | None) -> Item:
    level, name, message = doctor.kiro_agent_check(root, kiro.kiro_home(environ, home), run)
    return {"level": level, "name": name, "message": message[:MAX_MESSAGE]}


def jdk_item(root: Path, config_path: Path | None, environ: Mapping[str, str]) -> Item:
    _, config = doctor.config_check(root, config_path, environ)
    (level, name, message), _ = doctor.jdk_check(config, environ)
    item: Item = {"level": level, "name": name, "message": message[:MAX_MESSAGE]}
    if level == "fail":
        item["fix"] = "groovyscope jdk install"
        item["safe"] = False
    return item


def jar_item(environ: Mapping[str, str]) -> Item:
    level, name, message = doctor.jar_check(environ)
    item: Item = {"level": level, "name": name, "message": message[:MAX_MESSAGE]}
    if level == "fail":
        item["fix"] = "groovyscope fetch"
        item["safe"] = False
    return item


def render(report: Report) -> list[str]:
    """One line per item, doctor style, then what was repaired and what is left."""
    marks = {"ok": "ok  ", "info": "info", "warn": "warn", "fail": "FAIL"}
    lines = [
        f"{marks.get(item['level'], item['level']):4}  {item['name']:12} {item['message']}" for item in report["items"]
    ]
    lines += [f"      {'':12} fix: {item['fix']}" for item in report["items"] if item.get("fix")]
    for done in report["repaired"]:
        lines.append(f"repaired: {done}")
    return lines


def render_agent(report: Report, limit: int = 3800) -> str:
    """The compact block the agent start hook injects: a header, what was repaired, what needs a decision, and
    what is fine, within ``limit`` characters so Kiro never truncates it mid-sentence."""
    counts = {level: sum(1 for item in report["items"] if item["level"] == level) for level in ("ok", "warn", "fail")}
    header = (
        f"groovyscope {report['version']} checkup for {report['root']}: {counts['ok']} ok, {len(report['repaired'])} "
        f"repaired, {len(report['todo'])} to decide, {counts['fail']} failing"
    )
    lines = [header]
    for done in report["repaired"]:
        lines.append(f"repaired: {done}")
    for todo in report["todo"]:
        lines.append(f"todo: {todo}")
    fine = [f"{item['name']} ({item['message']})" for item in report["items"] if item["level"] == "ok"]
    if fine:
        lines.append("ok: " + "; ".join(fine))
    text = "\n".join(lines)
    return text if len(text) <= limit else text[: limit - 1] + "…"
