"""Environment checks behind ``groovyscope doctor``: pure report building, no printing."""

from __future__ import annotations

import json
import sys
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from groovyscope import __version__, daemon, kiro_agent, profiles
from groovyscope.config import load_config_with_notes
from groovyscope.jar import locate_jar
from groovyscope.java import RECOMMENDED_JDK, JdkInfo, discover, jdk_status
from groovyscope.kiro import has_global_setup, kiro_home, present_files
from groovyscope.provision import jdk_dir, provisioned_homes

MINIMUM_PYTHON = (3, 12)

Check = tuple[str, str, str]  # (level, name, message); level in ok | warn | fail | info


def python_check(version_info: tuple[int, ...] = tuple(sys.version_info[:3])) -> Check:
    rendered = ".".join(str(part) for part in version_info[:3])
    if version_info[:2] < MINIMUM_PYTHON:
        return ("fail", "python", f"{rendered} is below {MINIMUM_PYTHON[0]}.{MINIMUM_PYTHON[1]}")
    return ("ok", "python", rendered)


def config_check(
    root: Path, config_path: Path | None, environ: Mapping[str, str]
) -> tuple[Check, dict[str, Any] | None]:
    try:
        config, notes = load_config_with_notes(root, config_path, environ)
    except (ValueError, OSError) as exc:
        return (("fail", "config", str(exc).replace("\n", " ")), None)
    source = str(config_path) if config_path else "defaults + .groovyscope.yaml when present"
    trusted = "trusted workspace" if config["workspace"].get("trust") else "untrusted workspace"
    if notes:
        return (("warn", "config", f"{source}; {trusted}; " + "; ".join(notes)), config)
    return (("ok", "config", f"{source}; {trusted}"), config)


def jdk_check(config: Mapping[str, Any] | None, environ: Mapping[str, str]) -> tuple[Check, JdkInfo | None]:
    jdk_section = (config or {}).get("jdk", {})
    configured = jdk_section.get("home")
    minimum = int(jdk_section.get("minimum", 17))
    provisioned = provisioned_homes(jdk_dir(environ))
    found = discover(configured, environ, minimum=minimum, provisioned=provisioned)
    info = found["jdk"]
    level, message = jdk_status(info, minimum, RECOMMENDED_JDK)
    if level == "fail" and info is None and jdk_section.get("autoProvision"):
        message += "; jdk.autoProvision is on, so the next `groovyscope lsp` will download Temurin"
    if found["notes"]:
        message += "; " + "; ".join(found["notes"])
        if level == "ok":
            level = "warn"
    return ((level, "jdk", message), info)


def jar_check(environ: Mapping[str, str]) -> Check:
    info = locate_jar(environ)
    if info["error"]:
        return ("fail", "engine jar", info["error"])
    if info["source"] in {"bundled", "fetched"}:
        codenarc = f", CodeNarc {info['codenarc']}" if info.get("codenarc") else ""
        return (
            "ok",
            "engine jar",
            f"{info['path']} (version {info['version']}{codenarc}, {info['source']}, checksum verified)",
        )
    return ("warn", "engine jar", f"{info['path']} from GROOVYSCOPE_JAR (not checksum verified)")


PROJECT_MARKERS = (
    ".groovyscope.yaml",
    "settings.gradle",
    "settings.gradle.kts",
    "build.gradle",
    "build.gradle.kts",
    "pom.xml",
    "Jenkinsfile",
)


def kiro_check(root: Path, global_home: Path | None = None) -> Check:
    presence = present_files(root)
    if global_home is not None and has_global_setup(global_home):
        if presence[".kiro/settings/lsp.json"]:
            return ("ok", "kiro files", f"global setup in {global_home}; project LSP entry present")
        if not any((root / marker).exists() for marker in PROJECT_MARKERS):
            return ("info", "kiro files", f"global setup in {global_home}; not in a Groovy project here")
        return ("warn", "kiro files", f"global setup in {global_home}; run `groovyscope kiro init` in this project")
    missing = [name for name, present in presence.items() if not present]
    if not missing:
        return ("ok", "kiro files", "all four present")
    if len(missing) == len(presence):
        return ("info", "kiro files", "none found; run `groovyscope kiro init --global`, then `kiro init` per project")
    return ("warn", "kiro files", "missing: " + ", ".join(missing))


# Kiro CLI 3.0 makes the V3 engine the default and retires the Classic engine that read V2 agent files.
KIRO_V3_DEFAULT_MAJOR = 3


def kiro_agent_check(root: Path, global_home: Path, run: kiro_agent.KiroRunner | None) -> Check:
    """Which Kiro CLI is installed and which format the groovy agent is in (project first, then global)."""
    version = kiro_agent.kiro_version(run)
    label = f"kiro-cli {version}" if version else "kiro-cli not found on PATH"
    for agent in (root / ".kiro" / "agents" / "groovy.json", global_home / "agents" / "groovy.json"):
        if agent.is_file():
            try:
                found = kiro_agent.classify(agent.read_text(encoding="utf-8"), run)
            except OSError as exc:
                return ("warn", "kiro agent", f"{label}; cannot read {agent}: {exc}")
            meaning = AGENT_FORMAT_MEANING.get(found, found)
            level = "ok" if found in {"v2", "universal", "v3"} else "warn"
            message = f"{label}; {agent} is {found} ({meaning})"
            if found == "v2" and (kiro_agent.major_version(version) or 0) >= KIRO_V3_DEFAULT_MAJOR:
                level = "warn"
                message += (
                    "; Kiro CLI 3 runs the V3 engine, which skips a Classic (V2) agent: "
                    "`groovyscope kiro repair --agent-format universal` (or `v3` without kiro-cli)"
                )
            elif found == "v2" and kiro_agent.supports_upgrade(run):
                message += (
                    "; this kiro-cli can load it in both engines: `groovyscope kiro repair --agent-format universal`"
                )
            hidden = agent_hidden_servers(agent, root, global_home)
            if hidden:
                level = "warn"
                message += f"; its tools list hides these MCP servers: {', '.join(hidden)} (`groovyscope kiro repair`)"
            return (level, "kiro agent", message)
    return ("info", "kiro agent", f"{label}; no groovy agent yet (run `groovyscope kiro init --global`)")


def agent_hidden_servers(agent: Path, root: Path, global_home: Path) -> list[str]:
    """Configured MCP servers whose tools the agent cannot use."""
    try:
        document = json.loads(agent.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    servers = kiro_agent.mcp_servers([global_home / "settings" / "mcp.json", root / ".kiro" / "settings" / "mcp.json"])
    return kiro_agent.hidden_servers(document, servers) if isinstance(document, dict) else []


AGENT_FORMAT_MEANING = {
    "v2": "loaded by the V2 engine, the Kiro CLI 2.x default",
    "universal": "loaded by the V2 and V3 engines",
    "universal-out-of-sync": "edited after its V3 rules were generated; run `groovyscope kiro repair`",
    "v3": "loaded by the V3 engine only",
    "unknown": "a format this groovyscope does not know; left as it is",
    "external": "managed outside the project files; left as it is",
}


def daemon_check(root: Path, environ: Mapping[str, str]) -> Check:
    details = daemon.info(root, environ)
    if details["running"]:
        engine = (details["status"] or {}).get("engine", "?")
        warning = daemon.memory_warning(details["status"])
        if warning:
            return ("warn", "daemon", f"running on {details['socket']} (engine {engine}); {warning}")
        return ("ok", "daemon", f"running on {details['socket']} (engine {engine})")
    if details["outOfMemory"]:
        return ("warn", "daemon", daemon.out_of_memory_advice(details["log"]))
    return ("info", "daemon", f"not running; starts with the first `groovyscope lsp` session (log: {details['log']})")


def profiles_check(root: Path, config: Mapping[str, Any] | None, home: Path) -> Check:
    found = profiles.discover(root, (config or {}).get("profiles", {}), home)
    if found["errors"]:
        return ("warn", "profiles", "; ".join(found["errors"][:3]))
    if not found["profiles"]:
        return ("info", "profiles", "none (optional; see docs/profiles.md)")
    return ("ok", "profiles", ", ".join(f"{p['name']} ({p['path']})" for p in found["profiles"]))


def build_report(
    root: Path,
    config_path: Path | None,
    environ: Mapping[str, str],
    home: Path | None = None,
    kiro: kiro_agent.KiroRunner | None = None,
) -> list[Check]:
    config_result, config = config_check(root, config_path, environ)
    jdk_result, _ = jdk_check(config, environ)
    return [
        ("ok", "groovyscope", __version__),
        python_check(),
        config_result,
        jdk_result,
        jar_check(environ),
        kiro_check(root, kiro_home(environ, home or Path.home())),
        kiro_agent_check(root, kiro_home(environ, home or Path.home()), kiro),
        profiles_check(root, config, home or Path.home()),
        daemon_check(root, environ),
    ]


def has_failures(report: list[Check]) -> bool:
    return any(level == "fail" for level, _, _ in report)
