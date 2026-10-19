"""The ``groovyscope`` command line. Thin click wrappers over pure functions in the sibling modules."""

from __future__ import annotations

import contextlib
import hashlib
import importlib
import json
import logging
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.parse
from collections.abc import Callable, Mapping, Sequence
from importlib import resources
from pathlib import Path
from typing import Any

import click

from groovyscope import (
    __version__,
    benchmark,
    changes,
    checks,
    checkup,
    diagnostics,
    hooks,
    kiro_agent,
    kiro_steer,
    practices,
    profiles,
    provision,
    relay,
    reports,
    rpc,
    test_results,
    trust,
    verify,
    workspace_init,
)
from groovyscope import classpath as classpath_export
from groovyscope import daemon as daemon_lifecycle
from groovyscope import fetch as fetch_engine
from groovyscope.config import dump_yaml, load_config, load_config_with_notes
from groovyscope.doctor import build_report, has_failures
from groovyscope.edits import (
    FileChangedError,
    FileEdit,
    changed_line_count,
    counts_by_path,
    file_edits,
    write_document,
)
from groovyscope.jar import locate_jar, read_manifest
from groovyscope.java import JdkInfo, discover
from groovyscope.kiro import (
    BACKUP_SUFFIX,
    KIRO_INIT_WARNING,
    apply_changes,
    backup_path,
    has_global_setup,
    kiro_home,
    plan_changes,
    text_diff,
    unified_diff,
    write_atomically,
)
from groovyscope.launch import MIN_JDK_FOR_AUTO_CDS, build_engine_command
from groovyscope.render import compact_json, table

LOG_ENV = "GROOVYSCOPE_LOG"
LOG = logging.getLogger("groovyscope.cli")
CONFIG_ENV = "GROOVYSCOPE_CONFIG_JSON"
BASELINE_PATH = Path(".groovyscope") / "contract-baseline.json"
LEVEL_MARKS = {"ok": "ok  ", "warn": "warn", "fail": "FAIL", "info": "info"}

ExecFn = Callable[[str, Sequence[str]], Any]


def configure_logging(
    environ: dict[str, str] | None = None, *, level: str | None = None, log_file: Path | None = None
) -> None:
    """``--debug``/``--verbose`` win over ``GROOVYSCOPE_LOG``; the default is warnings only."""
    env = os.environ if environ is None else environ
    diagnostics.configure(diagnostics.level_of(level or env.get(LOG_ENV)), log_file)


def apply_config_logging(obj: dict[str, Any], effective: dict[str, Any]) -> None:
    """``logging.level``/``logging.file`` from the configuration apply when no flag or GROOVYSCOPE_LOG chose already,
    so the LSP and MCP processes Kiro starts honour .groovyscope.yaml. Flags reach the engine the same way."""
    section = effective.setdefault("logging", {})
    if obj.get("log_level"):
        section["level"] = obj["log_level"]
    if obj.get("log_file"):
        section["file"] = str(obj["log_file"])
    if not obj.get("log_level") and not os.environ.get(LOG_ENV):
        file_setting = section.get("file")
        diagnostics.configure(diagnostics.level_of(section.get("level")), Path(file_setting) if file_setting else None)


@click.group(context_settings={"help_option_names": ["-h", "--help"]})
@click.version_option(__version__, prog_name="groovyscope")
@click.option(
    "--config",
    "config_path",
    type=click.Path(path_type=Path, dir_okay=False),
    default=None,
    envvar="GROOVYSCOPE_CONFIG",
    help="Path to .groovyscope.yaml (default: ./.groovyscope.yaml when present).",
)
@click.option(
    "--root",
    type=click.Path(path_type=Path, file_okay=False, exists=True),
    default=None,
    help="Workspace root (default: current directory).",
)
@click.option("--debug", is_flag=True, help="Debug logging here and in the engine (implies --verbose).")
@click.option("-v", "--verbose", is_flag=True, help="Report each step (JDK, JAR, configuration, daemon, downloads).")
@click.option(
    "--log-file",
    type=click.Path(path_type=Path, dir_okay=False),
    default=None,
    help="Also append log records to this file (the engine writes there too).",
)
@click.pass_context
def main(
    ctx: click.Context,
    *,
    config_path: Path | None,
    root: Path | None,
    debug: bool,
    verbose: bool,
    log_file: Path | None,
) -> None:
    """Groovy 5/6 code intelligence for AI coding agents."""
    level = "debug" if debug else "info" if verbose else None
    configure_logging(level=level, log_file=log_file)
    ctx.obj = {
        "config_path": config_path,
        "root": (root or Path.cwd()).resolve(),
        "log_level": level,
        "log_file": log_file,
    }
    LOG.debug("groovyscope %s on Python %s, root %s", __version__, sys.version.split()[0], ctx.obj["root"])


@main.command("checkup")
@click.option(
    "--agent",
    "for_agent",
    is_flag=True,
    help="The compact block the Kiro agent's start hook injects: applies the safe repairs, never fails.",
)
@click.option("--fix", is_flag=True, help="Apply the safe repairs (Kiro files, project LSP entry, benchmark corpus).")
@click.option("--json", "as_json", is_flag=True, help="Machine-readable report.")
@click.pass_obj
def checkup_command(obj: dict[str, Any], *, for_agent: bool, fix: bool, as_json: bool) -> None:
    """Everything to do after an install or upgrade, as one report with the exact fix per item."""
    environ = dict(os.environ)
    if for_agent and not checkup.is_project(obj["root"]):
        # The start hook of any agent groovyscope is attached to: in a folder with no Groovy, nothing to check or
        # repair, and nothing to install on the user's machine.
        click.echo(checkup.IDLE_NOTE)
        return
    try:
        report = checkup.build(
            obj["root"],
            obj["config_path"],
            environ,
            Path.home(),
            fix=fix or for_agent,
            run=None if for_agent else _KIRO(),
            config_hash=config_hash_or_none(obj),
        )
    except Exception as exc:
        if for_agent:
            click.echo(f"groovyscope checkup could not run: {exc}")
            return
        raise
    if for_agent:
        click.echo(checkup.render_agent(report))
        return
    if as_json:
        click.echo(compact_json(report))
        return
    for line in checkup.render(report):
        click.echo(line)
    if any(item["level"] == "fail" for item in report["items"]):
        sys.exit(1)


def config_hash_or_none(obj: dict[str, Any]) -> str | None:
    """The hash of the configuration this process would hand to the engine, or None when it cannot be loaded."""
    try:
        effective = load_config(obj["root"], obj["config_path"], dict(os.environ))
    except (ValueError, OSError):
        return None
    hand_over_config(obj, effective)
    return config_hash()


@main.group("benchmark")
def benchmark_group() -> None:
    """The bundled benchmark corpus (CodeNarc src/main), the fixed workspace of docs/benchmark.md."""


@benchmark_group.command("path")
@click.option("--to", "target", type=click.Path(path_type=Path, file_okay=False), default=None, help="Unpack here.")
@click.option("--refresh", is_flag=True, help="Restore the bundled files even when the corpus is up to date.")
def benchmark_path(target: Path | None, refresh: bool) -> None:
    """Print where the corpus is (unpacking it first when needed); --to DIR unpacks it into DIR instead."""
    destination = unpack_benchmark(dict(os.environ), refresh=refresh, target=target)
    if destination is None:
        sys.exit(1)
    click.echo(str(destination))


@main.command()
def version() -> None:
    """Print the package and bundled engine versions."""
    manifest = read_manifest()
    engine = manifest.get("version", "unknown") if manifest else "not bundled"
    click.echo(f"groovyscope {__version__}")
    click.echo(f"engine {engine}")
    if manifest:
        click.echo(f"codenarc {manifest.get('codenarc', 'unknown')}")


@main.command()
@click.option(
    "--bundle",
    type=click.Path(path_type=Path, dir_okay=False),
    default=None,
    help="Also write a zip for a bug report: this report, versions, configuration, daemon log, Kiro's groovyscope "
    "log lines (no source code, no profile contents, no Kiro chat).",
)
@click.pass_obj
def doctor(obj: dict[str, Any], bundle: Path | None) -> None:
    """Check the JDK, engine JAR, configuration, Kiro files and profiles; exit 1 on hard failures."""
    report = build_report(obj["root"], obj["config_path"], dict(os.environ), kiro=_KIRO())
    click.echo(table([(LEVEL_MARKS[level], name, message) for level, name, message in report]))
    if bundle is not None:
        try:
            effective: dict[str, Any] | None = load_config(obj["root"], obj["config_path"], dict(os.environ))
        except (ValueError, OSError):
            effective = None
        manifest = read_manifest()
        written = diagnostics.bundle(
            bundle,
            doctor_lines=diagnostics.lines_of(report),
            versions={
                "groovyscope": __version__,
                "engine": (manifest or {}).get("version"),
                "codenarc": (manifest or {}).get("codenarc"),
            },
            effective_config=effective,
            daemon_log=daemon_paths(obj["root"])[1],
            environ=dict(os.environ),
        )
        click.echo(f"diagnostics bundle written to {written}")
    if has_failures(report):
        sys.exit(1)


@main.command()
@click.option("-n", "--lines", default=200, show_default=True, help="How many lines to show.")
@click.option("--follow", "-f", "follow_log", is_flag=True, help="Keep printing new lines (Ctrl-C to stop).")
@click.option("--kiro", "from_kiro", is_flag=True, help="Show groovyscope lines from Kiro CLI's LSP and MCP logs.")
@click.pass_obj
def logs(obj: dict[str, Any], lines: int, follow_log: bool, from_kiro: bool) -> None:
    """Show the engine daemon's log for this workspace (where engine debug output goes), or Kiro's view."""
    if from_kiro:
        for name, found in diagnostics.kiro_lines(dict(os.environ), lines).items():
            click.echo(f"== {diagnostics.kiro_log_dir(dict(os.environ)) / name}")
            for line in found:
                click.echo(line)
        return
    log_file = daemon_paths(obj["root"])[1]
    click.echo(f"== {log_file}", err=True)
    for line in diagnostics.tail(log_file, lines):
        click.echo(line)
    if follow_log:
        with contextlib.suppress(KeyboardInterrupt):
            diagnostics.follow(log_file, click.echo)


@main.group()
def config() -> None:
    """Inspect the effective configuration."""


@config.command("validate")
@click.option("--show", is_flag=True, help="Print the effective configuration as YAML.")
@click.pass_obj
def config_validate(obj: dict[str, Any], show: bool) -> None:
    """Merge defaults, file and environment, then validate against the schema."""
    try:
        effective = load_config(obj["root"], obj["config_path"], dict(os.environ))
    except (ValueError, OSError) as exc:
        click.echo(str(exc), err=True)
        sys.exit(1)
    if show:
        click.echo(dump_yaml(effective), nl=False)
    else:
        click.echo("configuration OK")


@main.group()
def kiro() -> None:
    """Install or repair the Kiro CLI integration files (workspace, or ~/.kiro with --global)."""


def profile_guidance_for(obj: dict[str, Any], *, global_scope: bool = False) -> str:
    """Steering text from the active runtime profiles (in a project, only those that apply to it); empty when
    there are none or the config is invalid."""
    return checkup.profile_guidance(
        obj["root"], obj["config_path"], dict(os.environ), Path.home(), global_scope=global_scope
    )


def diff_size(diff: str) -> str:
    """`` (+3 -1)`` for a unified diff: the lines it adds and removes, headers left out."""
    if not diff:
        return ""
    lines = diff.splitlines()
    added = sum(1 for line in lines if line.startswith("+") and not line.startswith("+++"))
    removed = sum(1 for line in lines if line.startswith("-") and not line.startswith("---"))
    return f" (+{added} -{removed})"


def run_kiro_merge(
    obj: dict[str, Any],
    *,
    force: bool,
    backup: bool,
    dry_run: bool,
    global_scope: bool = False,
    full: bool = False,
    agent_format: str = "auto",
    show_diff: bool = False,
) -> None:
    home = kiro_home(dict(os.environ), Path.home())
    base = home if global_scope else obj["root"]
    lsp_only = not global_scope and not full and has_global_setup(home)
    if lsp_only:
        click.echo(f"global groovyscope setup found in {home}: writing only the project's LSP entry (--full for all)")
    try:
        changes = plan_changes(
            base,
            force=force,
            global_scope=global_scope,
            profile_guidance=profile_guidance_for(obj, global_scope=global_scope),
            lsp_only=lsp_only,
            agent_format=agent_format,
            run=_KIRO(),
        )
    except ValueError as exc:
        click.echo(str(exc), err=True)
        sys.exit(1)
    if global_scope:
        click.echo(f"Kiro home: {base}")
    for change in changes:
        diff = unified_diff(change) if change["action"] != "unchanged" else ""
        click.echo(f"{change['action']:9} {change['path']}{diff_size(diff)}")
        if change.get("note"):
            click.echo(f"          note: {change.get('note')}")
        if diff and (show_diff or dry_run):
            click.echo(diff, nl=False)
    if not (show_diff or dry_run) and any(change["action"] != "unchanged" for change in changes):
        click.echo("(--diff shows the changes line by line)")
    if dry_run:
        click.echo("dry run: nothing written")
    else:
        written = apply_changes(base, changes, backup=backup)
        click.echo(f"{len(written)} file(s) written" if written else "nothing to do")
        if written and backup and any(c["before"] is not None and c["action"] == "update" for c in changes):
            click.echo(f"originals kept as <file>{BACKUP_SUFFIX}")
    if global_scope:
        if not dry_run:
            unpack_benchmark(dict(os.environ))
        click.echo(
            "next: in each Groovy project run `groovyscope kiro init` once; Kiro CLI reads LSP servers only from "
            "the project's .kiro/settings/lsp.json"
        )
        return
    click.echo(f"note: {KIRO_INIT_WARNING}", err=True)
    click.echo("next: restart Kiro CLI, then run /code status (expect: groovyscope (groovy) - initialized)")


def unpack_benchmark(environ: dict[str, str], *, refresh: bool = False, target: Path | None = None) -> Path | None:
    """Unpack the bundled benchmark corpus (CodeNarc src/main) into the user's home, or ``target``; one line of
    output, never an exception."""
    destination = target or benchmark.corpus_root(Path.home(), environ)
    try:
        result = benchmark.ensure(destination, refresh=refresh)
    except (OSError, ValueError) as exc:
        click.echo(f"benchmark corpus: could not unpack into {destination}: {exc}", err=True)
        return None
    if result is None:
        click.echo("benchmark corpus: this package bundles none", err=True)
        return None
    if result["action"] != "up-to-date":
        click.echo(
            f"benchmark corpus {result['action']} at {result['path']} (CodeNarc {result['version']}, "
            f"{result['files']} files)"
        )
    return Path(result["path"])


GLOBAL_HELP = "Write the MCP server, groovy agent and steering to the Kiro home (~/.kiro, or $KIRO_HOME)."
FULL_HELP = "In a project, write all files even when the global setup already provides the MCP server and agent."
FORMAT_HELP = (
    "Agent file format: auto keeps the existing file's format (new files are universal when kiro-cli can write it); "
    "v2 for the V2 engine, universal for both engines, v3 for the V3 engine only."
)
FORMAT_CHOICE = click.Choice(list(kiro_agent.REQUESTABLE))


@kiro.command("init")
@click.option("--global", "global_scope", is_flag=True, help=GLOBAL_HELP)
@click.option("--full", is_flag=True, help=FULL_HELP)
@click.option("--agent-format", type=FORMAT_CHOICE, default="auto", show_default=True, help=FORMAT_HELP)
@click.option("--diff", "show_diff", is_flag=True, help="Show each change line by line, not only a summary.")
@click.option("--force", is_flag=True, help="Replace existing files instead of merging into them.")
@click.option("--no-backup", is_flag=True, help="Do not write <file>.bak before overwriting.")
@click.option("--dry-run", is_flag=True, help="Show the diff without writing anything.")
@click.pass_obj
def kiro_init(
    obj: dict[str, Any],
    *,
    global_scope: bool,
    full: bool,
    agent_format: str,
    force: bool,
    no_backup: bool,
    dry_run: bool,
    show_diff: bool,
) -> None:
    """Write lsp.json, mcp.json, the groovy agent and the steering file (merging, never clobbering)."""
    run_kiro_merge(
        obj,
        force=force,
        backup=not no_backup,
        dry_run=dry_run,
        global_scope=global_scope,
        full=full,
        agent_format=agent_format,
        show_diff=show_diff,
    )


ATTACH_HELP = (
    "Give other Kiro agents groovyscope (AGENTS by name; without names, every agent that calls groovyscope with a "
    "part missing): the steering, the MCP server when the agent cannot reach it, the read-only groovyscope tools "
    "allowed, and the start, write and stop hooks. The agent's own tools, name, shell rules and format are kept, "
    "and a .bak of each changed file is written."
)


def attach_agents(obj: dict[str, Any], agents: Sequence[str], *, dry_run: bool) -> None:
    global_home = kiro_home(dict(os.environ), Path.home())
    configured = kiro_agent.mcp_servers(
        [global_home / "settings" / "mcp.json", obj["root"] / ".kiro" / "settings" / "mcp.json"]
    )
    files = kiro_steer.agent_files(obj["root"], global_home)
    if agents:
        chosen = kiro_steer.named(files, agents)
        for name in sorted(set(agents) - {name for name, _ in chosen}):
            click.echo(f"{name}: no such agent in {obj['root'] / '.kiro' / 'agents'} or {global_home / 'agents'}")
    else:
        chosen = [(name, path) for name, path, _ in kiro_steer.incomplete(files, configured)]
        if not chosen:
            click.echo("every agent that calls groovyscope has its steering and hooks; name an agent to add it")
    run = _KIRO()
    for name, path in chosen:
        existing = path.read_text(encoding="utf-8")
        agent = kiro_steer.read_agent(path) or {}
        missing = kiro_steer.missing_parts(agent, configured)
        if not missing:
            click.echo(f"{name} ({path}): already has groovyscope")
            continue
        if dry_run:
            click.echo(f"{name} ({path}): would add {', '.join(missing)}")
            continue
        merged = kiro_steer.attached_text(existing, kiro_steer.attachment(agent, configured, global_home), run)
        if merged["text"] == existing:
            click.echo(f"{name} ({path}): left unchanged; {merged['note'] or 'nothing to add'}")
            continue
        write_atomically(backup_path(path), existing)
        write_atomically(path, merged["text"])
        note = f"; {merged['note']}" if merged["note"] else ""
        click.echo(f"{name} ({path}): added {', '.join(missing)} ({merged['format']} format kept{note}); restart it")


@kiro.command("attach", help=ATTACH_HELP)
@click.argument("agents", nargs=-1)
@click.option("--dry-run", is_flag=True, help="Name the agents and the parts to add without writing anything.")
@click.pass_obj
def kiro_attach(obj: dict[str, Any], agents: tuple[str, ...], *, dry_run: bool) -> None:
    attach_agents(obj, agents, dry_run=dry_run)


@kiro.command("steer", hidden=True, help="Alias of `kiro attach` (0.21.1).")
@click.argument("agents", nargs=-1)
@click.option("--dry-run", is_flag=True)
@click.pass_obj
def kiro_steer_command(obj: dict[str, Any], agents: tuple[str, ...], *, dry_run: bool) -> None:
    attach_agents(obj, agents, dry_run=dry_run)


@kiro.command("repair")
@click.option("--global", "global_scope", is_flag=True, help=GLOBAL_HELP)
@click.option("--full", is_flag=True, help=FULL_HELP)
@click.option("--agent-format", type=FORMAT_CHOICE, default="auto", show_default=True, help=FORMAT_HELP)
@click.option("--dry-run", is_flag=True, help="Show the diff without writing anything.")
@click.option("--diff", "show_diff", is_flag=True, help="Show each change line by line, not only a summary.")
@click.pass_obj
def kiro_repair(
    obj: dict[str, Any], *, global_scope: bool, full: bool, agent_format: str, dry_run: bool, show_diff: bool
) -> None:
    """Re-merge groovyscope's entries after `/code init`, a manual edit or a Kiro upgrade changed them."""
    run_kiro_merge(
        obj,
        force=False,
        backup=True,
        dry_run=dry_run,
        global_scope=global_scope,
        full=full,
        agent_format=agent_format,
        show_diff=show_diff,
    )


def cds_archive_for(
    effective: dict[str, Any], jdk: JdkInfo, jar_sha256: str | None, environ: dict[str, str]
) -> Path | None:
    """The CDS archive to use, or None when disabled, unsupported by the JDK, or the JAR is unverified."""
    if not effective["daemon"].get("cds", True) or jdk["version"] < MIN_JDK_FOR_AUTO_CDS or not jar_sha256:
        return None
    return daemon_lifecycle.cache_dir(environ) / "cds" / f"engine-{jar_sha256[:16]}.jsa"


def resolve_jdk(effective: dict[str, Any], environ: dict[str, str]) -> JdkInfo | None:
    """A JDK meeting ``jdk.minimum``; with ``jdk.autoProvision`` on, Temurin is downloaded when none is found."""
    minimum = int(effective["jdk"]["minimum"])
    provisioned = provision.provisioned_homes(provision.jdk_dir(environ))
    found = discover(effective["jdk"].get("home"), environ, minimum=minimum, provisioned=provisioned)
    for note in found["notes"]:
        click.echo(f"groovyscope: {note}", err=True)
    jdk = found["jdk"]
    LOG.debug("JDK: %s", jdk)
    if jdk is not None and jdk["version"] >= minimum:
        return jdk
    if not effective["jdk"].get("autoProvision"):
        return None
    feature = max(minimum, provision.DEFAULT_FEATURE)
    click.echo(
        f"groovyscope: no JDK {minimum}+ found; jdk.autoProvision is on, downloading Temurin {feature}", err=True
    )
    try:
        installed = provision.provision_jdk(
            provision.jdk_dir(environ), feature=feature, vendor=str(effective["jdk"].get("vendor", "temurin"))
        )
    except (OSError, ValueError, KeyError) as exc:
        click.echo(f"groovyscope: JDK provisioning failed: {exc}", err=True)
        return None
    return {"home": installed["home"], "version": installed["version"], "source": "provisioned"}


def resolve_engine(obj: dict[str, Any]) -> tuple[list[str], dict[str, Any]]:
    """Resolve configuration, JDK and JAR; exit 1 with a message on any failure."""
    try:
        effective, notes = load_config_with_notes(obj["root"], obj["config_path"], dict(os.environ))
    except (ValueError, OSError) as exc:
        click.echo(str(exc), err=True)
        sys.exit(1)
    for note in notes:
        click.echo(f"groovyscope: {note}", err=True)
    apply_config_logging(obj, effective)
    LOG.debug(
        "configuration: %s; workspace %s; `groovyscope config validate --show` prints it",
        obj["config_path"] or "defaults + .groovyscope.yaml when present",
        "trusted" if effective["workspace"].get("trust") else "untrusted",
    )
    jdk = resolve_jdk(effective, dict(os.environ))
    if jdk is None:
        click.echo("groovyscope: no suitable JDK found; run `groovyscope doctor`", err=True)
        sys.exit(1)
    jar = locate_jar(dict(os.environ))
    LOG.debug("engine JAR: %s", jar)
    if jar["error"] or jar["path"] is None:
        click.echo(f"groovyscope: {jar['error']}", err=True)
        sys.exit(1)
    archive = cds_archive_for(effective, jdk, jar["actual_sha256"], dict(os.environ))
    if archive is not None:
        archive.parent.mkdir(parents=True, exist_ok=True)
    dumps = daemon_lifecycle.cache_dir(dict(os.environ)) / "dumps"
    command = build_engine_command(
        Path(jdk["home"]), Path(jar["path"]), effective, [], cds_archive=archive, dump_dir=dumps
    )
    obj["engine_version"] = jar["version"]
    obj["jdk_home"] = jdk["home"]
    LOG.debug("engine command: %s", " ".join(command))
    return (command, effective)


def bundled_config(relative: str) -> Path:
    """A configuration file shipped inside the package (rulesets, formatter settings)."""
    return Path(str(resources.files("groovyscope").joinpath("_templates/config").joinpath(relative)))


def engine_view(
    obj: dict[str, Any], effective: dict[str, Any], runtime_profiles: Sequence[dict[str, Any]] = ()
) -> dict[str, Any]:
    """The effective configuration with bundled defaults resolved to real paths and the runtime profiles
    discovered for the workspace, as the engine needs it."""
    lint = dict(effective["lint"])
    lint["ruleset"] = (
        str(Path(lint["ruleset"]).expanduser())
        if lint.get("ruleset")
        else str(bundled_config("codenarc/groovyscope.groovy"))
    )
    lint["jenkinsRuleset"] = (
        str(Path(lint["jenkinsRuleset"]).expanduser())
        if lint.get("jenkinsRuleset")
        else str(bundled_config("codenarc/jenkins.groovy"))
    )
    workspace = workspace_init.with_source_sets(Path(obj["root"]), dict(effective.get("workspace", {})))
    listed = workspace.get("classpathFile")
    if listed:
        exported = classpath_export.read_file(Path(obj["root"]) / Path(listed).expanduser())
        workspace["classpath"] = list(dict.fromkeys([*workspace.get("classpath", []), *exported]))
    return {
        **effective,
        "lint": lint,
        "workspace": workspace,
        "root": str(obj["root"]),
        "profiles": list(runtime_profiles),
    }


def discover_profiles(obj: dict[str, Any], effective: dict[str, Any]) -> profiles.Discovery:
    """Runtime profiles for the workspace; problems are reported on stderr and the profile is skipped."""
    found = profiles.discover(obj["root"], effective.get("profiles", {}), Path.home())
    for problem in found["errors"]:
        click.echo(f"groovyscope: profile skipped: {problem}", err=True)
    return found


def hand_over_config(obj: dict[str, Any], effective: dict[str, Any]) -> None:
    """The engine reads the validated, effective configuration from the environment (see EngineConfig)."""
    found = discover_profiles(obj, effective)
    trusted = bool(effective["workspace"].get("trust"))
    if not trusted:
        for note in profiles.untrusted_jar_notes(found, obj["root"]):
            click.echo(f"groovyscope: {note}", err=True)
    runtime = profiles.engine_profiles(found, obj["root"], trusted=trusted)
    os.environ[CONFIG_ENV] = json.dumps(engine_view(obj, effective, runtime), sort_keys=True)


def daemon_paths(root: Path) -> tuple[Path, Path, Path]:
    """``(socket, log, cache)`` for a workspace root."""
    environ = dict(os.environ)
    return (
        daemon_lifecycle.socket_path(root, environ),
        daemon_lifecycle.log_path(root, environ),
        daemon_lifecycle.cache_dir(environ),
    )


def config_hash() -> str:
    """SHA-256 of the configuration handed to the engine, as the engine reports it in its status."""
    return hashlib.sha256(os.environ.get(CONFIG_ENV, "").encode("utf-8")).hexdigest()


RELOAD_METHOD = "groovyscope/reloadConfig"
RELOAD_TIMEOUT_SECONDS = 60.0


def reload_if_stale(socket_file: Path, status: dict[str, Any]) -> bool:
    """A daemon running another configuration (a re-exported classpath, an edited .groovyscope.yaml, changed
    profiles) is handed the current one and applies it in place. True when a reload was sent and accepted; when
    the engine cannot take it, the user is told how to restart instead."""
    running = status.get("configHash")
    if not (isinstance(running, str) and running and running != config_hash()):
        return False
    try:
        _RPC(socket_file, RELOAD_METHOD, {"config": os.environ.get(CONFIG_ENV, "")}, RELOAD_TIMEOUT_SECONDS)
    except (OSError, RuntimeError, ValueError) as exc:
        click.echo(
            f"groovyscope: the daemon runs an older configuration and could not reload it ({exc}); "
            "run `groovyscope daemon restart` to apply it",
            err=True,
        )
        return False
    LOG.info("daemon configuration reloaded in place")
    return True


def config_source(obj: dict[str, Any]) -> Callable[[], tuple[str, str] | None]:
    """A function the MCP server calls before each engine request: the current effective configuration as
    ``(hash, json)``, recomputed only when the configuration file or the classpath file changed on disk (stat
    calls otherwise), or None when the configuration cannot be loaded (the engine keeps the last good one)."""
    memo: dict[str, Any] = {"stamp": None, "effective": None}
    value: list[tuple[str, str] | None] = [None]

    def stamps() -> tuple[tuple[int, int] | None, ...]:
        config_file = obj["config_path"] or obj["root"] / ".groovyscope.yaml"
        listed = (memo.get("effective") or {}).get("workspace", {}).get("classpathFile")
        classpath_file = obj["root"] / Path(listed or classpath_export.DEFAULT_FILE).expanduser()
        found: list[tuple[int, int] | None] = []
        for file in (config_file, classpath_file):
            try:
                stat = file.stat()
                found.append((stat.st_mtime_ns, stat.st_size))
            except OSError:
                found.append(None)
        return tuple(found)

    def current() -> tuple[str, str] | None:
        stamp = stamps()
        if stamp == memo["stamp"]:
            return value[0]
        try:
            effective = load_config(obj["root"], obj["config_path"], dict(os.environ))
            hand_over_config(obj, effective)
        except (ValueError, OSError) as exc:
            LOG.warning("configuration not reloaded: %s", exc)
            memo["stamp"] = stamp
            return value[0]
        memo.update({"stamp": stamp, "effective": effective})
        value[0] = (config_hash(), os.environ[CONFIG_ENV])
        return value[0]

    return current


def ensure_daemon(obj: dict[str, Any], command: list[str], effective: dict[str, Any]) -> tuple[Path, dict[str, Any]]:
    """Start the workspace daemon when needed (replacing one from another engine version). Raises on failure."""
    socket_file, log_file, cache = daemon_paths(obj["root"])
    daemon_command = daemon_lifecycle.daemon_command(
        command, socket_file, int(effective["daemon"]["idleTimeoutMinutes"]), cache
    )
    status = daemon_lifecycle.ensure_running(
        daemon_command,
        socket_file,
        log_file,
        dict(os.environ),
        expected_engine=obj.get("engine_version"),
        settings=effective.get("daemon", {}),
    )
    reload_if_stale(socket_file, status)
    return (socket_file, status)


def run_through_daemon(obj: dict[str, Any], command: list[str], effective: dict[str, Any]) -> int | None:
    """Start the daemon if needed and relay stdio to it. None when that is impossible (caller falls back). A daemon
    that answered the probe but is gone by the time the relay connects (it exited idle, `daemon stop` elsewhere)
    is started once more; nothing from stdin is read before the connection stands, so nothing is lost."""
    for attempt in range(2):
        try:
            socket_file, _ = ensure_daemon(obj, command, effective)
            return _RELAY(socket_file)
        except OSError as exc:
            if attempt == 0:
                click.echo(f"groovyscope: daemon went away before the session started ({exc})", err=True)
                click.echo("groovyscope: starting it again", err=True)
                continue
            click.echo(f"groovyscope: daemon unavailable ({exc}); running the engine directly", err=True)
        except RuntimeError as exc:
            click.echo(f"groovyscope: daemon unavailable ({exc}); running the engine directly", err=True)
            return None
    return None


def write_if_unchanged(edit: FileEdit, refused: list[str]) -> bool:
    """Writes the edit unless the file changed on disk since it was read, in which case the file joins ``refused``
    with the reason and nothing is written. True when written."""
    try:
        write_document(edit["path"], edit["after"], expected=edit["before"])
    except FileChangedError as exc:
        refused.append(f"{edit['path'].as_uri()} ({exc})")
        return False
    return True


def exec_process(path: str, argv: Sequence[str]) -> None:
    """Replace this process with the engine (never returns on success)."""
    os.execv(path, list(argv))  # noqa: S606 - deliberate: the CLI becomes the engine process


_EXEC: ExecFn = exec_process
_KIRO: Callable[[], kiro_agent.KiroRunner | None] = kiro_agent.kiro_runner
_RELAY: Callable[[Path], int] = relay.main
_RPC: Callable[..., Any] = rpc.request
_PROBE: Callable[[Path], dict[str, Any] | None] = daemon_lifecycle.probe


def mcp_module() -> Any:
    """The MCP server module, imported on first use: it pulls in the MCP SDK (about a second of start-up), which
    `hook`, `checkup` and the other short commands Kiro runs on every turn never need."""
    return importlib.import_module("groovyscope.mcp_server")


def serve_mcp(*args: Any, **kwargs: Any) -> int:
    return int(mcp_module().serve(*args, **kwargs))


_MCP_SERVE: Callable[..., int] = serve_mcp
RPC_TIMEOUT_SECONDS = 600.0
SEVERITY_NAMES = {1: "error", 2: "warning", 3: "info", 4: "hint"}
POSITION_PARTS = 3  # path:line:col


def daemon_socket_for(obj: dict[str, Any]) -> tuple[Path, dict[str, Any]]:
    """Make sure the workspace daemon runs and return its socket with the effective configuration."""
    command, effective = resolve_engine(obj)
    hand_over_config(obj, effective)
    try:
        socket_file, _ = ensure_daemon(obj, command, effective)
    except (OSError, RuntimeError) as exc:
        click.echo(f"groovyscope: {exc}", err=True)
        sys.exit(1)
    return (socket_file, effective)


def to_uris(root: Path, paths: Sequence[Path]) -> list[str]:
    return [(root / path).resolve().as_uri() for path in paths]


def uri_to_path(uri: str) -> Path:
    return Path(urllib.parse.unquote(urllib.parse.urlparse(uri).path))


def engine_call(
    socket_file: Path, method: str, params: dict[str, Any], timeout: float = RPC_TIMEOUT_SECONDS
) -> dict[str, Any]:
    result = engine_result(socket_file, method, params, timeout)
    return dict(result) if isinstance(result, dict) else {}


def engine_result(socket_file: Path, method: str, params: dict[str, Any], timeout: float = RPC_TIMEOUT_SECONDS) -> Any:
    """One engine request, whatever shape it answers with (a list for impact reports); exits on failure."""
    try:
        return _RPC(socket_file, method, params, timeout)
    except (OSError, RuntimeError, ValueError) as exc:
        click.echo(f"groovyscope: {method} failed: {exc}", err=True)
        sys.exit(1)


def imported_rows(
    root: Path, effective: Mapping[str, Any], explicit: Sequence[Path], paths: Sequence[Path]
) -> list[dict[str, Any]]:
    """SARIF findings as CLI rows (the build's reports plus EXPLICIT files), limited to PATHS when given; files
    that cannot be read are reported on stderr and skipped."""
    found, notes = reports.merge_sarif(root, reports.reports_settings(effective)["sarif"], explicit=list(explicit))
    for note in notes:
        click.echo(f"groovyscope: {note}", err=True)
    wanted = [shown_path(root / p, root).rstrip("/") for p in paths]
    rows = []
    for finding in found:
        if wanted and not any(finding["path"] == w or finding["path"].startswith(w + "/") for w in wanted):
            continue
        rows.append(
            {
                "path": finding["path"],
                "line": finding["line"],
                "column": finding["col"],
                "severity": finding["severity"],
                "level": practices.LEVELS.get(str(finding["severity"]), 3),
                "source": finding["source"],
                "code": finding["code"],
                "message": finding["message"],
            }
        )
    return rows


def finding_rows(diagnostics: dict[str, list[dict[str, Any]]], root: Path) -> list[dict[str, Any]]:
    """Flatten engine diagnostics into sortable rows with workspace-relative paths and 1-based positions."""
    rows: list[dict[str, Any]] = []
    for uri, items in diagnostics.items():
        path = uri_to_path(uri)
        try:
            shown = path.relative_to(root).as_posix()
        except ValueError:
            shown = path.as_posix()
        for item in items:
            start = item.get("range", {}).get("start", {})
            severity = int(item.get("severity", 3))
            rows.append(
                {
                    "path": shown,
                    "line": int(start.get("line", 0)) + 1,
                    "column": int(start.get("character", 0)) + 1,
                    "severity": SEVERITY_NAMES.get(severity, "info"),
                    "level": severity,
                    "source": item.get("source", ""),
                    "code": item.get("code", ""),
                    "message": item.get("message", ""),
                }
            )
    rows.sort(key=lambda row: (row["path"], row["line"], row["column"], row["level"]))
    return rows


@main.command()
@click.option("--stdio", is_flag=True, help="Serve LSP over stdin/stdout (the only transport today).")
@click.pass_obj
def lsp(obj: dict[str, Any], stdio: bool) -> None:
    """Run the engine's Language Server for Kiro CLI or an editor."""
    if not stdio:
        click.echo("groovyscope lsp: pass --stdio (Kiro's lsp.json does)", err=True)
        sys.exit(2)
    command, effective = resolve_engine(obj)
    hand_over_config(obj, effective)
    if effective["daemon"]["enabled"]:
        code = run_through_daemon(obj, command, effective)
        if code is not None:
            sys.exit(code)
    _EXEC(command[0], [*command, "lsp", "--stdio", "--cache-dir", str(daemon_paths(obj["root"])[2])])


@main.command()
@click.argument("paths", nargs=-1, type=click.Path(path_type=Path))
@click.option(
    "--profile", type=click.Choice(["default", "jenkins"]), default=None, help="Rule set profile (default: by file)."
)
@click.option(
    "--include", default="lint,compile,typecheck,practices", show_default=True, help="Comma-separated analyses."
)
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output (same as --format json).")
@click.option(
    "--format",
    "output_format",
    type=click.Choice(["text", "json", "short", "sarif"]),
    default="text",
    show_default=True,
    help="short: file:line:col: severity: message [code]; sarif: SARIF 2.1.0 for code scanning.",
)
@click.option(
    "--import-sarif",
    "sarif_paths",
    multiple=True,
    type=click.Path(path_type=Path, dir_okay=False),
    help="Merge findings from a SARIF log (SpotBugs...), in addition to reports.sarif and the build's default.",
)
@click.pass_obj
def check(
    obj: dict[str, Any],
    *,
    paths: tuple[Path, ...],
    profile: str | None,
    include: str,
    as_json: bool,
    output_format: str,
    sarif_paths: tuple[Path, ...],
) -> None:
    """Lint, compile, type-check and practice-check Groovy files (the whole workspace when no PATHS are given)."""
    socket_file, effective = daemon_socket_for(obj)
    params: dict[str, Any] = {
        "root": str(obj["root"]),
        "uris": to_uris(obj["root"], paths),
        "include": [part.strip() for part in include.split(",") if part.strip()],
    }
    if profile:
        params["profile"] = profile
    result = engine_call(socket_file, "groovyscope/check", params)
    rows = finding_rows(result.get("diagnostics", {}), obj["root"])
    rows += imported_rows(obj["root"], effective, sarif_paths, paths)
    summary = result.get("summary", {})
    if as_json or output_format == "json":
        click.echo(compact_json({"findings": rows, "summary": summary}))
    elif output_format == "short":
        for line in practices.short_lines(rows):
            click.echo(line)
    elif output_format == "sarif":
        click.echo(json.dumps(practices.sarif(rows), indent=2))
    else:
        for row in rows:
            location = f"{row['path']}:{row['line']}:{row['column']}"
            click.echo(f"{location}: {row['severity']} [{row['source']}/{row['code']}] {row['message']}")
        click.echo(
            f"{summary.get('errors', 0)} error(s), {summary.get('warnings', 0)} warning(s), "
            f"{summary.get('information', 0)} info in {summary.get('files', 0)} file(s)"
        )
    fail_on = int(effective["lint"]["failOnPriority"])
    if any(row["level"] <= fail_on for row in rows):
        sys.exit(1)


@main.command("hook")
@click.option("--event", default=None, help="The Kiro hook event when stdin does not name it (tests, scripts).")
@click.pass_obj
def hook_command(obj: dict[str, Any], event: str | None) -> None:
    """What Kiro's hooks run: reads the hook JSON from stdin. After a write (PostToolUse) the Groovy file just
    written is checked and the findings printed for the agent; on Stop, with hooks.stopGate on, the turn is refused
    while changed Groovy files have compile errors. Never fails the session."""
    payload = hooks.parse_payload(sys.stdin.read())
    name = hooks.event_name(payload, event)
    if name not in {"postToolUse", "stop"}:
        return
    # Everything that needs no engine comes first: most hook events end here, in milliseconds.
    settings = hooks.settings(load_config(obj["root"], obj["config_path"], dict(os.environ)))
    paths = hook_targets(obj["root"], name, payload, settings)
    if not paths:
        return
    # A hook never starts the engine: a cold JVM would hold the agent for its start-up and compile. The LSP
    # session Kiro opens starts the daemon; until then the hook says so and steps aside.
    socket_file = daemon_paths(obj["root"])[0]
    if _PROBE(socket_file) is None:
        click.echo("groovyscope: hook skipped, the engine daemon is not running (it starts with the Kiro session)")
        return
    text = (
        hook_after_write(socket_file, obj["root"], paths, settings)
        if name == "postToolUse"
        else hook_stop_gate(socket_file, obj["root"], paths, settings)
    )
    if text:
        click.echo(text)


def hook_targets(root: Path, name: str, payload: Mapping[str, Any], settings: hooks.HookSettings) -> list[Path]:
    """The files the event is about, or nothing when the event needs no engine: the setting is off, the write
    touched no Groovy file, or no Groovy file changed in the working tree."""
    if name == "postToolUse":
        return hooks.written_paths(payload, root) if settings["afterWrite"] else []
    if not settings["stopGate"]:
        return []
    return hooks.changed_groovy_files(root, changes.default_runner)


def hook_after_write(socket_file: Path, root: Path, paths: Sequence[Path], settings: hooks.HookSettings) -> str:
    """The after-write block for the Groovy files a write touched; empty when there is nothing to say."""
    rows = hook_check_rows(socket_file, root, paths, settings["afterWriteInclude"], settings["timeoutSeconds"])
    return "" if rows is None else hooks.render_after_write(rows, paths, root, settings["afterWriteMaxFindings"])


def hook_stop_gate(socket_file: Path, root: Path, changed: Sequence[Path], settings: hooks.HookSettings) -> str:
    """The Stop answer: a block decision as JSON while changed Groovy files have compile errors, else empty."""
    rows = hook_check_rows(socket_file, root, changed, ["compile"], settings["timeoutSeconds"])
    decision = hooks.stop_decision(rows or [], settings["afterWriteMaxFindings"])
    return json.dumps(decision) if decision else ""


def hook_check_rows(
    socket_file: Path, root: Path, paths: Sequence[Path], include: Sequence[str], timeout: int
) -> list[dict[str, Any]] | None:
    """The findings for ``paths`` within ``timeout`` seconds, or None (with one line printed) when the engine did
    not answer in time: a hook must never hold the agent hostage."""
    params: dict[str, Any] = {"root": str(root), "uris": to_uris(root, list(paths)), "include": list(include)}
    try:
        result = _RPC(socket_file, "groovyscope/check", params, float(timeout))
    except (OSError, RuntimeError, ValueError) as exc:
        click.echo(f"groovyscope: hook check skipped ({exc})")
        return None
    diagnostics = result.get("diagnostics", {}) if isinstance(result, dict) else {}
    return finding_rows(diagnostics, root)


@main.command("review")
@click.argument("paths", nargs=-1, type=click.Path(path_type=Path))
@click.option("--changed", is_flag=True, help="Review what this branch changed against --base, as a merge request.")
@click.option("--base", default=None, help="Base branch for --changed (default: the repository's default branch).")
@click.option("--staged", is_flag=True, help="With --changed: review the index instead of the branch.")
@click.option("--top", default=8, show_default=True, help="Findings shown per group.")
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def review_command(
    obj: dict[str, Any],
    *,
    paths: tuple[Path, ...],
    changed: bool,
    base: str | None,
    staged: bool,
    top: int,
    as_json: bool,
) -> None:
    """Review PATHS (or the whole workspace) against the practices checklist, or with --changed review the
    branch's changed Groovy files as a merge request reviewer: blocking, should fix, by design, impact, verdict."""
    socket_file, effective = daemon_socket_for(obj)
    tools = workspace_tools(obj, socket_file, effective)
    if changed:
        result = tools["review_changes"](base, staged, top)
    else:
        relative = [shown_path(obj["root"] / p, obj["root"]) for p in paths]
        result = tools["review"](relative or None, top)
    if as_json:
        click.echo(compact_json(result))
    else:
        for line in render_review(result):
            click.echo(line)
    if (
        result.get("error")
        or result.get("counts", {}).get("blocking")
        or any(group.get("worst", 9) <= 1 for group in result.get("groups", []))
    ):
        sys.exit(1)


def run_build(command: Sequence[str], cwd: Path, timeout: int) -> tuple[int, str]:
    """Run the project's build (``groovyscope verify``); the exit code and the combined output."""
    try:
        done = subprocess.run(list(command), cwd=cwd, capture_output=True, text=True, timeout=timeout, check=False)
    except FileNotFoundError as exc:
        return (-1, f"cannot run {command[0]}: {exc}")
    except subprocess.TimeoutExpired:
        return (-2, f"the build ran longer than {timeout}s (verify.timeoutSeconds) and was stopped")
    return (done.returncode, (done.stdout or "") + (done.stderr or ""))


_VERIFY_RUNNER: Callable[[Sequence[str], Path, int], tuple[int, str]] = run_build


def report_mtime(label: str) -> float:
    return Path(label).stat().st_mtime


def run_tests(command: Sequence[str], root: Path, timeout: int) -> dict[str, Any]:
    """Run the Gradle command (with the init script when it filters tests) and read the JUnit XML reports this run
    wrote: the exit code, the failed test names Gradle printed, the results and, on a failure without reports,
    the output's tail."""
    with tempfile.TemporaryDirectory(prefix="groovyscope-verify-") as folder:
        script = Path(folder) / "verify.init.gradle"
        script.write_text(verify.INIT_SCRIPT, encoding="utf-8")
        started = time.time() - 1  # file systems with one second timestamps
        code, output = _VERIFY_RUNNER(verify.with_init_script(command, script), root, timeout)
    results = test_results.summarise([root], keep=verify.written_since(started, report_mtime))
    gradle: dict[str, Any] = {"exitCode": code, "failed": verify.failed_tests(output), "results": results}
    if code != 0 and not results["failed"]:
        gradle["tail"] = verify.tail(output)
    return gradle


def build_lines(gradle: Mapping[str, Any], command: Sequence[str], limit: int) -> list[str]:
    """``build: passed`` or ``build: FAILED``, the results with each failure's message and place, and a note when
    the filters matched no test."""
    results = gradle["results"]
    lines = ["build: passed" if gradle["exitCode"] == 0 else f"build: FAILED (exit {gradle['exitCode']})"]
    if results["reports"]:
        lines += test_results.lines(results, limit)
    elif "--tests" in command and gradle["exitCode"] == 0:
        lines.append("  note: no test report was written; the --tests filters may have matched no test")
    if not results["failed"]:
        lines += [f"  failed: {name}" for name in gradle["failed"]]
        lines += [f"  {line}" for line in gradle.get("tail", []) if not gradle["failed"]]
    if results["reports"] and not results["tests"] and "--tests" in command:
        lines.append("  note: the --tests filters matched no test; run the classes instead")
    return lines


def changed_uncovered(root: Path, effective: Mapping[str, Any], base: str | None, *, staged: bool) -> dict[str, Any]:
    """Changed methods the run's JaCoCo report says no test executed."""
    configured = reports.reports_settings(effective)["jacoco"]
    report = reports.jacoco_file(root, configured)
    if report is None:
        return {"error": f"no JaCoCo report at {configured or reports.DEFAULT_JACOCO} after the run"}
    try:
        ranges = changes.ranges_by_path(changes.changed_files(root, base, staged=staged))
        gaps = reports.read_jacoco(report, root)
    except ValueError as exc:
        return {"error": str(exc)}
    uncovered = verify.uncovered_in_change(gaps, ranges)
    return {"report": shown_path(report, root), "uncovered": uncovered, "count": len(uncovered)}


def shown_limit(effective: Mapping[str, Any]) -> int:
    return int((effective.get("output") or {}).get("maxResults", 20))


def coverage_lines(coverage: Mapping[str, Any]) -> list[str]:
    if coverage.get("error"):
        return [f"coverage: {coverage['error']}"]
    if not coverage["count"]:
        return ["coverage: the tests ran every changed method"]
    lines = [f"coverage: {coverage['count']} changed method(s) no test executed"]
    return lines + [f"  {g['path']}:{g['line']}: {g['name']}" for g in coverage["uncovered"]]


def coverage_plan(plan_result: Mapping[str, Any]) -> dict[str, Any]:
    """The plan with the JaCoCo report task added to its command, or a note when the build has none."""
    args = list(plan_result.get("commandArgs") or [])
    if not args:
        return dict(plan_result)
    if not plan_result.get("coverageTask"):
        return {**plan_result, "note": "the build does not apply JaCoCo: --coverage has nothing to read"}
    args = verify.with_coverage(args, plan_result["coverageTask"])
    return {**plan_result, "commandArgs": args, "command": verify.quoted(args)}


@main.command("verify")
@click.option("--base", default=None, help="Base branch (default: the repository's default branch).")
@click.option("--staged", is_flag=True, help="Verify the index instead of the branch.")
@click.option(
    "--failed",
    is_flag=True,
    help="Run again only the tests that failed in the last JUnit XML reports (the workspace, or --results).",
)
@click.option(
    "--results",
    "results_from",
    multiple=True,
    type=click.Path(path_type=Path),
    help="With --failed: folders or ZIP archives (a CI artifacts.zip) holding TEST-*.xml.",
)
@click.option("--coverage", is_flag=True, help="Also build the JaCoCo report and list changed methods no test ran.")
@click.option("--dry-run", is_flag=True, help="Print the plan and the Gradle command without running it.")
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def verify_command(
    obj: dict[str, Any],
    *,
    base: str | None,
    staged: bool,
    failed: bool,
    results_from: tuple[Path, ...],
    coverage: bool,
    dry_run: bool,
    as_json: bool,
) -> None:
    """Verify the change in one step: review the changed lines, then compile the changed source sets and run the
    test methods the change reaches (through the call graph) with one Gradle command, and read the JUnit XML
    reports the run wrote. Tests of tasks outside `verify.runTasks` (integration tests that need CI credentials)
    are listed, not run. Exit 1 on a failed build or test, or a blocking finding."""
    if results_from and not failed:
        raise click.UsageError("--results goes with --failed")
    if failed:
        verify_failed(obj, results_from, dry_run=dry_run, as_json=as_json)
        return
    socket_file, effective = daemon_socket_for(obj)
    tools = workspace_tools(obj, socket_file, effective)
    result = tools["review_changes"](base, staged, 8)
    if result.get("error"):
        click.echo(f"groovyscope: {result['error']}", err=True)
        sys.exit(1)
    settings = effective.get("verify") or {}
    plan_result = result.get("tests") or {}
    lines = [f"verify against {result.get('base')}: {result.get('verdict')}"]
    lines += triage_lines(result.get("triage") or {})
    if coverage:
        plan_result = coverage_plan(plan_result)
    args = list(plan_result.get("commandArgs") or [])
    lines += verify.test_lines(plan_result)
    gradle: dict[str, Any] | None = None
    uncovered: dict[str, Any] | None = None
    if args and not dry_run:
        gradle = run_tests(args, obj["root"], int(settings.get("timeoutSeconds", 1800)))
        lines += build_lines(gradle, args, shown_limit(effective))
        if coverage and plan_result.get("coverageTask"):
            uncovered = changed_uncovered(obj["root"], effective, base, staged=staged)
            lines += coverage_lines(uncovered)
    elif args:
        lines.append("dry run: nothing was built")
    blocking = int((result.get("counts") or {}).get("blocking", 0))
    if as_json:
        keys = ("base", "mode", "verdict", "triage", "counts", "files")
        payload = {**{k: result.get(k) for k in keys}, "tests": plan_result, "build": gradle}
        click.echo(compact_json({**payload, "coverage": uncovered} if coverage else payload))
    else:
        for line in lines:
            click.echo(line)
    if blocking or (gradle is not None and gradle["exitCode"] != 0):
        sys.exit(1)


def verify_failed(obj: dict[str, Any], sources: Sequence[Path], *, dry_run: bool, as_json: bool) -> None:
    """``verify --failed``: the failures of the last reports run again, one ``--tests`` filter each."""
    try:
        effective = load_config(obj["root"], obj["config_path"], dict(os.environ))
    except (ValueError, OSError) as exc:
        click.echo(str(exc), err=True)
        sys.exit(1)
    root = obj["root"]
    last = test_results.summarise(test_results.default_sources(root, [str(p.resolve()) for p in sources]))
    wrapper = "./gradlew" if (root / "gradlew").is_file() else "gradle"
    command, unplaced = verify.rerun_command(wrapper, last["failed"])
    lines = [f"{len(last['failed'])} failed test(s) in {last['reports']} report(s)"]
    lines += [f"  not from a Gradle test task, run it with its own build: {name}" for name in unplaced]
    lines += [f"  note: {note}" for note in last["notes"]]
    gradle: dict[str, Any] | None = None
    if command:
        lines.append(f"run: {verify.quoted(command)}")
        if dry_run:
            lines.append("dry run: nothing was built")
        else:
            timeout = int((effective.get("verify") or {}).get("timeoutSeconds", 1800))
            gradle = run_tests(command, root, timeout)
            lines += build_lines(gradle, command, shown_limit(effective))
    if as_json:
        click.echo(compact_json({"last": last, "command": command, "unplaced": unplaced, "build": gradle}))
    else:
        for line in lines:
            click.echo(line)
    if gradle is not None and gradle["exitCode"] != 0:
        sys.exit(1)


@main.command("test-results")
@click.argument("sources", nargs=-1, type=click.Path(path_type=Path))
@click.option("--limit", default=20, show_default=True, type=click.IntRange(min=1), help="Failures shown.")
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def test_results_command(obj: dict[str, Any], *, sources: tuple[Path, ...], limit: int, as_json: bool) -> None:
    """Failing tests from JUnit XML reports (TEST-*.xml at any depth) under SOURCES: folders or ZIP archives such as
    a CI pipeline's artifacts.zip (default: the workspace). Each failure with its message, the first frame in the
    project's code and in the test. Exit 1 when a test failed or no report was found."""
    summary = test_results.summarise(test_results.default_sources(obj["root"], [str(p.resolve()) for p in sources]))
    if as_json:
        click.echo(compact_json(summary))
    else:
        for line in test_results.lines(summary, limit):
            click.echo(line)
    if summary["failed"] or not summary["reports"]:
        sys.exit(1)


def tool_settings(effective: Mapping[str, Any], classpath: Mapping[str, Any] | None = None) -> dict[str, Any]:
    """What the MCP tools read beyond the output section: duplicate thresholds, the build's reports and the
    classpath file to watch."""
    return {
        **effective["output"],
        "duplicates": effective.get("duplicates", {}),
        "diagram": effective.get("diagram", {}),
        "reports": reports.reports_settings(effective),
        "verify": effective.get("verify", {}),
        "classpath": dict(classpath or {}),
        "mcp": dict(effective.get("mcp") or {}),
    }


def trusted_now(obj: dict[str, Any]) -> bool:
    """Whether the workspace is trusted at this moment (the configuration read again, so a revoke counts)."""
    try:
        effective = load_config(obj["root"], obj["config_path"], dict(os.environ))
    except (OSError, ValueError):
        return False
    return bool(effective["workspace"].get("trust"))


def classpath_settings(obj: dict[str, Any], effective: Mapping[str, Any]) -> dict[str, Any]:
    """Where the exported classpath lives, whether jars are listed by hand, whether the workspace is trusted
    (so `refresh_classpath` may run the build) and which JDK the build should use."""
    workspace = effective.get("workspace", {})
    listed = workspace.get("classpathFile") or classpath_export.DEFAULT_FILE
    return {
        "file": str(obj["root"] / Path(listed).expanduser()),
        "configured": bool(workspace.get("classpath")),
        "trusted": bool(workspace.get("trust")),
        "isTrusted": lambda: trusted_now(obj),
        "javaHome": obj.get("jdk_home"),
    }


def triage_lines(triage: Mapping[str, Any]) -> list[str]:
    """``risks: GS002 78 (12 fixable), CatchException 46 · smells: ... · noise: ...`` and the fix command."""
    lines: list[str] = []
    for tier in ("risks", "smells", "noise"):
        found = triage.get(tier) or {}
        if found.get("count"):
            codes = ", ".join(
                f"{c['code']} {c['count']}" + (f" ({c['fixable']} fixable)" if c["fixable"] else "")
                for c in found["codes"]
            )
            lines.append(f"{tier} ({found['count']}): {codes}")
    fix = triage.get("fix")
    if fix:
        lines.append(f"safe fixes for {fix['count']}: `{fix['command']}`")
    return lines


def render_review(result: dict[str, Any]) -> list[str]:
    """The review as text: verdict first, then the groups a reviewer reads in order."""
    if result.get("error"):
        return [f"groovyscope: {result['error']}"]
    lines: list[str] = []
    if "verdict" in result:
        lines.append(f"verdict: {result['verdict']}")
        shown = ", ".join(f"{f['path']} ({f['status']}, {f['changedLines']} lines)" for f in result.get("files", []))
        lines.append(f"changed: {shown or 'nothing reviewable'} (against {result.get('base')})")
        lines += triage_lines(result.get("triage") or {})
        for name, title in (("blocking", "blocking"), ("shouldFix", "should fix"), ("nits", "nits")):
            items = result.get(name, [])
            if items:
                lines.append(f"{title} ({result['counts'][name]}):")
                lines += [f"  {f['path']}:{f['line']}: [{f.get('code')}] {f.get('message')}" for f in items]
        lines += verify.test_lines(result.get("tests") or {})
        for report in result.get("impact", []):
            lines.append(
                f"impact: {report['symbol']} ({report['kind']}, {report['path']}) risk {report['riskLevel']}, "
                f"callers {report['callers']}, tests {report['tests']}"
            )
        for site in result.get("risky", []):
            lines.append(f"added {site['kind']}: {site['path']}:{site['line']} {site.get('detail') or ''}".rstrip())
        elsewhere = result.get("elsewhere", {})
        if elsewhere.get("count"):
            lines.append(f"pre-existing in these files: {elsewhere['count']} ({elsewhere.get('byCode')})")
        for gap in result.get("uncovered", []):
            lines.append(f"uncovered: {gap['name']} ({gap['path']}:{gap['line']}, {gap['missed']} lines)")
        missing = result.get("missingDependencies")
        if missing:
            lines.append(f"missing dependencies in {missing['files']} file(s): {missing['fix']}")
        if result.get("imported"):
            lines.append(f"imported from the build's reports: {result['imported']}")
        return lines
    summary = result.get("summary", {})
    lines.append(
        f"{summary.get('errors', 0)} error(s), {summary.get('warnings', 0)} warning(s), "
        f"{summary.get('information', 0)} info in {summary.get('files', 0)} file(s)"
    )
    for group in result.get("groups", []):
        lines.append(f"{group['title']} ({group['total']}): {group['check']}")
        lines += [f"  {f['path']}:{f['line']}: [{f.get('code')}] {f.get('message')}" for f in group.get("findings", [])]
    return lines


@main.command("coverage")
@click.argument("paths", nargs=-1, type=click.Path(path_type=Path))
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def coverage_command(obj: dict[str, Any], *, paths: tuple[Path, ...], as_json: bool) -> None:
    """Classes and methods no test executed, from the build's JaCoCo report (reports.jacoco), most missed first."""
    try:
        effective = load_config(obj["root"], obj["config_path"], dict(os.environ))
    except (ValueError, OSError) as exc:
        click.echo(str(exc), err=True)
        sys.exit(1)
    configured = reports.reports_settings(effective)["jacoco"]
    report = reports.jacoco_file(obj["root"], configured)
    if report is None:
        click.echo(
            f"groovyscope: no JaCoCo report at {configured or reports.DEFAULT_JACOCO}; run the build's tests with "
            "jacocoTestReport first",
            err=True,
        )
        sys.exit(1)
    try:
        gaps = reports.filter_gaps(
            reports.read_jacoco(report, obj["root"]), [shown_path(obj["root"] / p, obj["root"]) for p in paths]
        )
        summary = reports.coverage_summary(report)
    except ValueError as exc:
        click.echo(f"groovyscope: {exc}", err=True)
        sys.exit(1)
    if as_json:
        click.echo(compact_json({"report": shown_path(report, obj["root"]), "coverage": summary, "gaps": gaps}))
        return
    click.echo(f"line coverage {summary['percent']}% ({summary['covered']} of {summary['lines']} lines)")
    for gap in gaps:
        click.echo(
            f"{gap['path']}:{gap['line']}: {gap['status']} {gap['kind']} {gap['name']} ({gap['missed']} lines missed)"
        )
    if not gaps:
        click.echo("no uncovered lines")


@main.command("explain-code")
@click.argument("target")
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def explain_code_command(obj: dict[str, Any], target: str, as_json: bool) -> None:
    """What TARGET does, as facts: TARGET is a class or member (pkg.Class, Class.method, Class#method) or a file."""
    socket_file, effective = daemon_socket_for(obj)
    tools = workspace_tools(obj, socket_file, effective)
    is_file = (obj["root"] / target).is_file() or target.endswith((".groovy", ".gradle", ".java"))
    result = tools["explain_code"](path=target) if is_file else tools["explain_code"](symbol=target)
    if as_json:
        click.echo(compact_json(result))
    else:
        for line in render_explanation(result):
            click.echo(line)
    if not result.get("found"):
        click.echo(f"no declaration found for {target}", err=True)
        sys.exit(2)


def workspace_tools(obj: dict[str, Any], socket_file: Path, effective: dict[str, Any]) -> dict[str, Any]:
    """The MCP tool functions bound to this workspace over the daemon socket, so CLI and agent answers match."""
    tools: dict[str, Any] = mcp_module().build_tools(
        obj["root"],
        lambda method, params: engine_result(socket_file, method, params),
        tool_settings(effective),
        dict(os.environ),
    )
    return tools


def testing_lines(testing: Mapping[str, Any]) -> list[str]:
    """A method's test seams: each collaborator and how to replace it, then the tests and their helpers."""
    lines = [f"  testing: {testing['direct']}"] if testing.get("direct") else []
    for item in testing.get("collaborators", []):
        stubbed = f" (stubbed at {item['stubbedAt']}: {item['example']})" if item.get("stubbedAt") else ""
        lines.append(f"  seam {item['target']} [{item['visibility']}]: {item['stub']}{stubbed}")
    for test in testing.get("tests", []):
        lines.append(f"  tested in {test['class']} ({test['path']}): helpers {', '.join(test['helpers']) or 'none'}")
    return lines


def render_explanation(result: dict[str, Any]) -> list[str]:
    lines: list[str] = []
    for item in result.get("items", []):
        kind = item.get("kind")
        if kind == "method":
            lines.append(
                f"{item.get('class')}.{item['name']} ({item.get('visibility')} method, "
                f"lines {item['lines'][0]}-{item['lines'][1]})"
            )
            lines.append(
                f"  returns {item.get('returns')}; parameters: {', '.join(item.get('parameters') or []) or 'none'}"
            )
            lines.append(f"  branches {item.get('branches')}; called from {item.get('callerCount')} site(s)")
            for key in ("calls", "writes", "reads", "throws", "catches", "externals"):
                if item.get(key):
                    shown = [c["target"] if isinstance(c, dict) else str(c) for c in item[key]]
                    lines.append(f"  {key}: {', '.join(shown)}")
            lines += testing_lines(item.get("testing") or {})
            continue
        if kind in {"field", "property"}:
            lines.append(f"{item.get('class')}.{item['name']}: {item.get('type')} {kind} ({item.get('visibility')})")
            continue
        lines.append(
            f"{item.get('kind')} {item['name']} ({item.get('path')}, lines {item['lines'][0]}-{item['lines'][1]})"
        )
        if item.get("extends") or item.get("implements"):
            lines.append(
                f"  extends {item.get('extends') or 'nothing'}; "
                f"implements {', '.join(item.get('implements') or []) or 'nothing'}"
            )
        if item.get("fields"):
            lines.append("  fields: " + ", ".join(f"{f['type']} {f['name']}" for f in item["fields"]))
        for responsibility in item.get("responsibilities", []):
            lines.append(f"  {responsibility['verb']}: {', '.join(responsibility['methods'])}")
        for method in item.get("methods", []):
            lines.append(
                f"  {method.get('visibility')} {method.get('returns') or 'constructor'} {method['name']}("
                f"{', '.join(method.get('parameters') or [])}) lines {method['lines'][0]}-{method['lines'][1]}, "
                f"branches {method.get('branches')}, callers {method.get('callerCount')}"
                + (f", writes {', '.join(method['writes'])}" if method.get("writes") else "")
                + (f", externals {', '.join(method['externals'])}" if method.get("externals") else "")
            )
        if item.get("externals"):
            lines.append(f"  touches: {', '.join(item['externals'])}")
        lines.append(f"  used by: {', '.join(item.get('usedBy') or []) or 'nothing in the workspace'}")
        lines.append(f"  uses: {', '.join(item.get('uses') or []) or 'nothing'}")
    return lines


@main.command("graph")
@click.argument("symbols", nargs=-1)
@click.option(
    "--path", "paths", multiple=True, type=click.Path(path_type=Path), help="Seed with every class of a file."
)
@click.option(
    "--kinds", default="", help="Comma-separated edge kinds: extends,implements,has,uses,calls (all by default)."
)
@click.option("--depth", default=1, show_default=True, help="Hops shown around the seeds.")
@click.option("--level", type=click.Choice(["class", "method"]), default="class", show_default=True)
@click.option("--max-nodes", default=60, show_default=True, help="Cap on nodes (best connected first).")
@click.option(
    "--format", "output_format", type=click.Choice(["drawio", "mermaid", "json"]), default="drawio", show_default=True
)
@click.option(
    "--out",
    type=click.Path(path_type=Path, dir_okay=False),
    default=None,
    help="Write here (drawio default: groovyscope-graph.drawio).",
)
@click.pass_obj
def graph_command(
    obj: dict[str, Any],
    *,
    symbols: tuple[str, ...],
    paths: tuple[Path, ...],
    kinds: str,
    depth: int,
    level: str,
    max_nodes: int,
    output_format: str,
    out: Path | None,
) -> None:
    """The relationships between classes (extends, implements, has, uses, calls) seeded by SYMBOLS or --path, as a
    draw.io file laid out on an A4 page, a Mermaid class diagram, or JSON."""
    socket_file, effective = daemon_socket_for(obj)
    tools = workspace_tools(obj, socket_file, effective)
    wanted = [k.strip() for k in kinds.split(",") if k.strip()]
    relative = [shown_path(obj["root"] / p, obj["root"]) for p in paths]
    if output_format == "drawio":
        target = out or Path("groovyscope-graph.drawio")
        result = tools["relationship_graph"](
            symbols=list(symbols),
            paths=relative,
            kinds=wanted,
            depth=depth,
            level=level,
            max_nodes=max_nodes,
            drawio_path=shown_path(obj["root"] / target, obj["root"]),
        )
        if result.get("error"):
            click.echo(f"groovyscope: {result['error']}", err=True)
            sys.exit(1)
        click.echo(
            f"{result['nodeCount']} class(es), {result['edgeCount']} relation(s) written to {result['written']} "
            f"({result['page']['orientation']} {result['page']['paper']}, font {result['page']['font']}px)"
        )
        lines = result.get("lines") or {}
        if lines:
            shared = (
                f", {lines['sharedHelpers']} shared helper(s) named in their callers' boxes"
                if lines.get("sharedHelpers")
                else ""
            )
            click.echo(
                f"lines: {lines.get('layout')} layout, {lines.get('crossings', 0)} crossing(s), "
                f"{lines.get('overlaps', 0)} overlap(s){shared}"
            )
            calls = lines.get("calls") or {}
            if calls:
                click.echo(
                    f"calls: {calls.get('read', 0)} read (blue), {calls.get('write', 0)} write (orange), "
                    f"{calls.get('unknown', 0)} unknown (grey); arrows point from caller to callee"
                )
        for problem in result.get("layoutProblems", []):
            click.echo(f"layout: {problem}", err=True)
        if result.get("layoutNote"):
            click.echo(f"note: {result['layoutNote']}", err=True)
        if result.get("truncated"):
            click.echo("note: the graph was capped; raise --max-nodes or narrow the seeds", err=True)
        return
    result = tools["relationship_graph"](
        symbols=list(symbols),
        paths=relative,
        kinds=wanted,
        depth=depth,
        level=level,
        max_nodes=max_nodes,
        mermaid=output_format == "mermaid",
    )
    if result.get("error"):
        click.echo(f"groovyscope: {result['error']}", err=True)
        sys.exit(1)
    text = result["mermaid"] if output_format == "mermaid" else compact_json(result)
    if out is not None:
        write_atomically(out if out.is_absolute() else obj["root"] / out, text)
        click.echo(f"written to {shown_path(out if out.is_absolute() else obj['root'] / out, obj['root'])}")
    else:
        click.echo(text, nl=output_format != "mermaid")


@main.command("naming")
@click.argument("paths", nargs=-1, type=click.Path(path_type=Path))
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output, with the declaration facts.")
@click.pass_obj
def naming_command(obj: dict[str, Any], *, paths: tuple[Path, ...], as_json: bool) -> None:
    """Naming findings (GS020 to GS027) for PATHS or the workspace; --json adds the facts behind each name."""
    socket_file, effective = daemon_socket_for(obj)
    tools = workspace_tools(obj, socket_file, effective)
    relative = [shown_path(obj["root"] / p, obj["root"]) for p in paths]
    result = tools["check_naming"](relative or None, limit=int(effective["output"]["maxResults"]))
    if as_json:
        click.echo(compact_json(result))
        return
    for finding in result.get("items", []):
        click.echo(
            f"{finding['path']}:{finding['line']}:{finding['col']}: {finding['severity']} "
            f"[{finding['code']}] {finding['message']}"
        )
    click.echo(f"{len(result.get('items', []))} naming finding(s); by code: {result.get('byCode', {})}")


@main.command("format")
@click.argument("paths", nargs=-1, type=click.Path(path_type=Path))
@click.option("--apply", "apply_changes", is_flag=True, help="Write the formatted files; otherwise only report.")
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def format_command(obj: dict[str, Any], paths: tuple[Path, ...], apply_changes: bool, as_json: bool) -> None:
    """Format Groovy files with the bundled formatter; exits 1 when files would change and --apply is not given."""
    socket_file, _ = daemon_socket_for(obj)
    result = engine_call(
        socket_file, "groovyscope/format", {"root": str(obj["root"]), "uris": to_uris(obj["root"], paths)}
    )
    rows: list[dict[str, Any]] = []
    changed, refused = file_edits(result.get("edits", {}), obj["root"])
    for edit in changed:
        if apply_changes and not write_if_unchanged(edit, refused):
            continue
        rows.append(
            {
                "path": shown_path(edit["path"], obj["root"]),
                "lines": changed_line_count(edit["before"], edit["after"]),
                "applied": apply_changes,
            }
        )
    for uri in refused:
        click.echo(f"groovyscope: refused an edit outside the workspace: {uri}", err=True)
    checked = int(result.get("checked", 0))
    if as_json:
        click.echo(compact_json({"files": rows, "checked": checked, "changed": len(rows)}))
    else:
        for row in rows:
            click.echo(f"{row['path']}: {row['lines']} line(s) changed{' (applied)' if row['applied'] else ''}")
        click.echo(f"{len(rows)} of {checked} file(s) {'formatted' if apply_changes else 'need formatting'}")
    if rows and not apply_changes:
        sys.exit(1)


@main.command()
@click.argument("paths", nargs=-1, type=click.Path(path_type=Path))
@click.option("--code", "codes", multiple=True, help="Only these codes (GS001, CompileStatic, ...); repeatable.")
@click.option("--apply", "apply_changes", is_flag=True, help="Write the fixes; otherwise only report them.")
@click.option("--changed", "only_changed", is_flag=True, help="Only the lines this branch changed (against --base).")
@click.option("--base", default=None, help="Base branch for --changed (default: the repository's default branch).")
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def fix(
    obj: dict[str, Any],
    paths: tuple[Path, ...],
    *,
    codes: tuple[str, ...],
    apply_changes: bool,
    only_changed: bool,
    base: str | None,
    as_json: bool,
) -> None:
    """Apply groovyscope's safe fixes (practice findings and mechanical lint fixes); exits 1 when fixes remain.
    With --changed, only on the lines this branch changed, so existing code is left as it is."""
    params: dict[str, Any] = {"root": str(obj["root"]), "uris": to_uris(obj["root"], paths), "codes": list(codes)}
    if only_changed:
        try:
            scope = changes.fix_scope(obj["root"], changes.changed_files(obj["root"], base))
        except ValueError as exc:
            click.echo(f"groovyscope: {exc}", err=True)
            sys.exit(1)
        if not scope["uris"]:
            click.echo("no changed Groovy lines to fix")
            return
        params.update(scope)
    socket_file, _ = daemon_socket_for(obj)
    result = engine_call(socket_file, "groovyscope/fix", params)
    rows: list[dict[str, Any]] = []
    changed, refused = file_edits(result.get("edits", {}), obj["root"])
    applied_codes = {uri_to_path(uri): codes for uri, codes in result.get("applied", {}).items()}
    fixed = counts_by_path(result.get("appliedCounts", {}))
    left = {shown_path(path, obj["root"]): counts for path, counts in counts_by_path(result.get("left", {})).items()}
    for edit in changed:
        if apply_changes and not write_if_unchanged(edit, refused):
            continue
        rows.append(
            {
                "path": shown_path(edit["path"], obj["root"]),
                "codes": applied_codes.get(edit["path"], []),
                "fixed": fixed.get(edit["path"], {}),
                "lines": changed_line_count(edit["before"], edit["after"]),
                "applied": apply_changes,
            }
        )
    rejected = {shown_path(uri_to_path(uri), obj["root"]): why for uri, why in result.get("rejected", {}).items()}
    rejected.update({shown_path(uri_to_path(uri), obj["root"]): "outside the workspace; refused" for uri in refused})
    if as_json:
        click.echo(compact_json({"files": rows, "left": left, "rejected": rejected}))
    else:
        for row in rows:
            state = " (applied)" if row["applied"] else ""
            summary = ", ".join(f"{k} {v}" for k, v in row["fixed"].items()) or ", ".join(row["codes"])
            click.echo(f"{row['path']}: {summary}, {row['lines']} line(s){state}")
        for path, counts in left.items():
            click.echo(f"{path}: left without a safe fix: {', '.join(f'{k} {v}' for k, v in counts.items())}")
        for skipped, why in rejected.items():
            click.echo(f"{skipped}: skipped, {why}")
        click.echo(f"{len(rows)} file(s) {'fixed' if apply_changes else 'with fixes available'}")
    if rows and not apply_changes:
        sys.exit(1)


@main.command()
@click.argument("code")
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def explain(obj: dict[str, Any], code: str, as_json: bool) -> None:
    """Explain a diagnostic code (GS001, NullChecker, a CodeNarc rule): why it matters and how to fix it."""
    socket_file, _ = daemon_socket_for(obj)
    answer = engine_call(socket_file, "groovyscope/explain", {"root": str(obj["root"]), "code": code})
    if not answer:
        click.echo(f"groovyscope: nothing known about {code}", err=True)
        sys.exit(1)
    if as_json:
        click.echo(compact_json(answer))
        return
    click.echo(f"{answer['code']}: {answer['title']}")
    click.echo(f"why: {answer['why']}")
    click.echo(f"fix: {answer['fix']}")
    if answer.get("link"):
        click.echo(f"see: {answer['link']}")


def shown_path(path: Path, root: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.as_posix()


def target_params(obj: dict[str, Any], target: str) -> dict[str, Any]:
    """``path:line:col`` (1-based) becomes a uri with a zero-based position; anything else is a symbol name."""
    parts = target.rsplit(":", 2)
    if len(parts) == POSITION_PARTS and parts[1].isdigit() and parts[2].isdigit():
        path = (obj["root"] / parts[0]).resolve()
        return {"uri": path.as_uri(), "line": int(parts[1]) - 1, "character": int(parts[2]) - 1}
    return {"symbol": target}


def describe_symbol(symbol: dict[str, Any], root: Path) -> str:
    location = symbol.get("location")
    where = ""
    if location:
        path = uri_to_path(location["uri"])
        try:
            shown = path.relative_to(root).as_posix()
        except ValueError:
            shown = path.as_posix()
        where = f" ({shown}:{int(location['line']) + 1}:{int(location['character']) + 1})"
    detail = symbol.get("detail") or symbol.get("name")
    container = symbol.get("container")
    return f"{container + '.' if container else ''}{detail}{where}"


def describe_site(site: dict[str, Any], root: Path) -> str:
    where = uri_to_path(site["site"]["uri"])
    try:
        shown = where.relative_to(root).as_posix()
    except ValueError:
        shown = where.as_posix()
    position = f"{shown}:{int(site['site']['line']) + 1}:{int(site['site']['character']) + 1}"
    return f"{position} in {describe_symbol(site['caller'], root)} [{site['confidence']}]"


@main.command()
@click.argument("target")
@click.option(
    "--change",
    type=click.Choice(["signature", "rename", "delete", "behaviour"]),
    default="signature",
    show_default=True,
)
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def impact(obj: dict[str, Any], target: str, change: str, as_json: bool) -> None:
    """What breaks if TARGET changes. TARGET is a qualified name (pkg.Class.method, Class#method) or path:line:col."""
    socket_file, _ = daemon_socket_for(obj)
    params = {"root": str(obj["root"]), "change": change, **target_params(obj, target)}
    result = _RPC_LIST(socket_file, "groovyscope/impact", params)
    if as_json:
        click.echo(compact_json(result))
        return
    if not result:
        click.echo(f"no declaration found for {target}", err=True)
        sys.exit(2)
    root = obj["root"]
    for report in result:
        counts = report["counts"]
        click.echo(f"{describe_symbol(report['symbol'], root)}")
        click.echo(f"  change: {report['change']}  risk: {report['riskLevel']} ({report['riskScore']}/100)")
        click.echo(
            f"  direct callers: {counts['direct']}  transitive: {counts['transitive']}  "
            f"overrides: {counts['overrides']}  tests: {counts['tests']}  dynamic (review): {counts['dynamicUnknowns']}"
        )
        for label, key in (
            ("direct", "directCallers"),
            ("transitive", "transitiveCallers"),
            ("dynamic", "dynamicUnknowns"),
        ):
            for site in report[key]:
                click.echo(f"    {label}: {describe_site(site, root)}")
        for override in report["overrides"]:
            click.echo(f"    override: {describe_symbol(override, root)}")
        for check in report["suggestedChecks"]:
            click.echo(f"  - {check}")


@main.command()
@click.argument("target")
@click.option("--direction", type=click.Choice(["in", "out"]), default="in", show_default=True)
@click.option("--depth", type=click.IntRange(1, 6), default=2, show_default=True)
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def callgraph(obj: dict[str, Any], target: str, direction: str, depth: int, as_json: bool) -> None:
    """Callers of (in) or calls made by (out) TARGET, to the given depth."""
    socket_file, _ = daemon_socket_for(obj)
    params = {"root": str(obj["root"]), "direction": direction, "depth": depth, **target_params(obj, target)}
    result = engine_call(socket_file, "groovyscope/callGraph", params)
    if as_json:
        click.echo(compact_json(result))
        return
    root = obj["root"]
    if not result.get("roots"):
        click.echo(f"no declaration found for {target}", err=True)
        sys.exit(2)
    nodes = len(result.get("nodes", []))
    edges = result.get("edges", [])
    heading = "incoming" if direction == "in" else "outgoing"
    click.echo(f"{heading} calls, depth {depth}, {nodes} node(s), {len(edges)} edge(s)")
    for edge in edges:
        caller = describe_symbol(edge["caller"], root)
        callee = describe_symbol(edge["callee"], root)
        click.echo(f"  {caller} -> {callee} [{edge['confidence']}]")


def _rpc_list(socket_file: Path, method: str, params: dict[str, Any]) -> list[dict[str, Any]]:
    try:
        result = _RPC(socket_file, method, params, RPC_TIMEOUT_SECONDS)
    except (OSError, RuntimeError, ValueError) as exc:
        click.echo(f"groovyscope: {method} failed: {exc}", err=True)
        sys.exit(1)
    return [dict(item) for item in result] if isinstance(result, list) else []


_RPC_LIST = _rpc_list


@main.command()
@click.pass_obj
def mcp(obj: dict[str, Any]) -> None:
    """Serve groovyscope's MCP tools on stdio (what .kiro/settings/mcp.json runs)."""
    socket_file, effective = daemon_socket_for(obj)

    def reconnect() -> Path:
        try:
            return daemon_socket_for(obj)[0]
        except SystemExit as exc:
            msg = "the engine daemon could not be restarted; run `groovyscope doctor`"
            raise ConnectionError(msg) from exc

    settings = tool_settings(effective, classpath_settings(obj, effective))
    sys.exit(
        _MCP_SERVE(obj["root"], socket_file, settings, dict(os.environ), reconnect, configuration=config_source(obj))
    )


@main.command()
@click.option(
    "--from",
    "source",
    default=None,
    help="URL (https) or local path of the engine JAR. Default: the GitHub Release matching this package.",
)
@click.option("--sha256", default=None, help="Expected SHA-256; otherwise SHA256SUMS.txt next to the JAR is used.")
@click.option("--engine-version", default=__version__, show_default=True, help="Engine version being fetched.")
def fetch(source: str | None, sha256: str | None, engine_version: str) -> None:
    """Fetch the engine JAR into the cache for installs that do not bundle one (checksum verified)."""
    environ = dict(os.environ)
    destination = fetch_engine.fetched_dir(environ)
    chosen = source or fetch_engine.default_source(engine_version)
    try:
        result = fetch_engine.fetch_jar(chosen, version=engine_version, destination=destination, sha256=sha256)
    except (OSError, ValueError) as exc:
        click.echo(f"groovyscope: fetch failed: {exc}", err=True)
        sys.exit(1)
    click.echo(f"fetched {result['path']} (version {result['version']}, sha256 {result['sha256'][:16]}...)")
    click.echo(f"from {result['source']}")


@main.group()
def jdk() -> None:
    """Provision a Temurin JDK when none is installed (never done unless asked or jdk.autoProvision is on)."""


@jdk.command("install")
@click.option("--feature", type=int, default=provision.DEFAULT_FEATURE, show_default=True, help="Java feature release.")
@click.option(
    "--vendor", type=click.Choice(list(provision.VENDORS)), default="temurin", show_default=True, help="JDK vendor."
)
def jdk_install(feature: int, vendor: str) -> None:
    """Download the latest JDK of a feature release (Eclipse Temurin or Amazon Corretto), verify its SHA-256 against
    the vendor's published checksum, and unpack it into groovyscope's cache."""
    environ = dict(os.environ)
    try:
        installed = provision.provision_jdk(provision.jdk_dir(environ), feature=feature, vendor=vendor)
    except (OSError, ValueError, KeyError) as exc:
        click.echo(f"groovyscope: JDK provisioning failed: {exc}", err=True)
        sys.exit(1)
    click.echo(f"installed {installed['release']} at {installed['home']}")
    click.echo("groovyscope will use it when no other JDK meets jdk.minimum; `groovyscope doctor` shows which wins")


@main.command("classpath")
@click.option("--tool", type=click.Choice(["gradle", "maven"]), default=None, help="Build tool (default: detected).")
@click.option("--timeout", default=classpath_export.DEFAULT_TIMEOUT_SECONDS, show_default=True, help="Seconds.")
@click.pass_obj
def classpath_command(obj: dict[str, Any], tool: str | None, timeout: int) -> None:
    """Ask the project's Gradle or Maven build for its compile and test classpath and write it to
    workspace.classpathFile, so dependencies resolve. Runs the build tool, so only in a trusted workspace."""
    try:
        effective = load_config(obj["root"], obj["config_path"], dict(os.environ))
    except (ValueError, OSError) as exc:
        click.echo(str(exc), err=True)
        sys.exit(1)
    if not effective["workspace"].get("trust"):
        click.echo(
            "groovyscope: `classpath` runs the project's build tool, which executes its build scripts; "
            "run `groovyscope trust` first if you trust this workspace",
            err=True,
        )
        sys.exit(1)
    environ = dict(os.environ)
    if not environ.get("JAVA_HOME"):
        jdk = discover(effective["jdk"].get("home"), environ, minimum=int(effective["jdk"]["minimum"]))["jdk"]
        if jdk is not None:
            environ["JAVA_HOME"] = jdk["home"]
    click.echo("running the build tool to resolve the classpath (this can take a while)…", err=True)
    target = obj["root"] / Path(effective["workspace"].get("classpathFile") or classpath_export.DEFAULT_FILE)
    try:
        with classpath_export.exclusive(target):
            result = classpath_export.export(obj["root"], environ, tool=tool, timeout=timeout)
            classpath_export.write_file(target, result, fingerprint=classpath_export.build_fingerprint(obj["root"]))
    except ValueError as exc:
        click.echo(f"groovyscope: {exc}", err=True)
        sys.exit(1)
    click.echo(
        f"{len(result['paths'])} classpath entries from {result['tool']} written to {shown_path(target, obj['root'])}"
    )
    if result["missing"]:
        click.echo(f"{len(result['missing'])} listed path(s) do not exist yet (not built?) and were left out", err=True)
    socket_file = daemon_paths(obj["root"])[0]
    status = daemon_lifecycle.probe(socket_file)
    if status is not None:
        hand_over_config(obj, load_config(obj["root"], obj["config_path"], dict(os.environ)))
        if reload_if_stale(socket_file, status):
            click.echo("the running daemon reloaded the new classpath; the next request recompiles against it")
        else:
            try:
                daemon_lifecycle.stop(socket_file)
            except (OSError, RuntimeError) as exc:
                click.echo(f"groovyscope: {exc}; run `groovyscope daemon restart` to apply the classpath", err=True)
            else:
                click.echo("the running daemon was stopped; the next request starts it with the new classpath")


@main.command("init")
@click.option("--dry-run", is_flag=True, help="Show the diff without writing anything.")
@click.pass_obj
def init_command(obj: dict[str, Any], dry_run: bool) -> None:
    """Write a starting .groovyscope.yaml from the workspace's layout and build files (existing settings win)."""
    try:
        target, current, after = workspace_init.plan(obj["root"])
    except (ValueError, OSError) as exc:
        click.echo(f"groovyscope: {exc}", err=True)
        sys.exit(1)
    if current == after:
        click.echo(f"{shown_path(target, obj['root'])} already has these settings")
    else:
        click.echo(text_diff(current, after, shown_path(target, obj["root"])), nl=False)
        if dry_run:
            click.echo("dry run: nothing written")
        else:
            if current is not None:
                write_atomically(backup_path(target), current)
            write_atomically(target, after)
            click.echo(f"wrote {shown_path(target, obj['root'])}")
    click.echo("next: `groovyscope trust` (if you trust this workspace), then `groovyscope classpath`")


@main.group("profile")
def profile_group() -> None:
    """Runtime profiles: which are active, whether a file is valid, what governs a file."""


@profile_group.command("list")
@click.pass_obj
def profile_list(obj: dict[str, Any]) -> None:
    """List the runtime profiles found for this workspace and where they came from; exit 1 on invalid ones."""
    try:
        effective = load_config(obj["root"], obj["config_path"], dict(os.environ))
    except (ValueError, OSError) as exc:
        click.echo(str(exc), err=True)
        sys.exit(1)
    found = profiles.discover(obj["root"], effective.get("profiles", {}), Path.home())
    for line in profiles.describe(found):
        click.echo(line)
    if found["errors"]:
        sys.exit(1)


@profile_group.command("install")
@click.argument("file", type=click.Path(path_type=Path, exists=True, dir_okay=False))
@click.option("--force", is_flag=True, help="Replace a profile file of the same name.")
def profile_install(file: Path, force: bool) -> None:
    """Validate a runtime profile and copy it to ~/.groovyscope/profiles/ (readable by you only), the way to bring
    a private profile to another machine without putting it in a repository."""
    try:
        problems = profiles.validate_profile(profiles.read_profile(file))
    except (OSError, ValueError) as exc:
        problems = (str(exc),)
    if problems:
        for problem in problems:
            click.echo(f"{file}: {problem}", err=True)
        sys.exit(1)
    folder = Path.home() / profiles.USER_DIR
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    target = folder / file.name
    if target.exists() and not force:
        click.echo(f"groovyscope: {target} exists; pass --force to replace it", err=True)
        sys.exit(1)
    write_atomically(target, file.read_text(encoding="utf-8"))
    target.chmod(0o600)
    click.echo(f"installed {target}; `groovyscope daemon restart` in open workspaces to apply it")


@profile_group.command("validate")
@click.argument("files", nargs=-1, required=True, type=click.Path(path_type=Path, exists=True, dir_okay=False))
def profile_validate(files: tuple[Path, ...]) -> None:
    """Validate profile files against the profile schema."""
    failed = False
    for file in files:
        try:
            problems = profiles.validate_profile(profiles.read_profile(file))
        except (OSError, ValueError) as exc:
            problems = (str(exc),)
        for problem in problems:
            click.echo(f"{file}: {problem}", err=True)
        failed = failed or bool(problems)
        if not problems:
            click.echo(f"{file}: ok")
    if failed:
        sys.exit(1)


@profile_group.command("status")
@click.argument("path", type=click.Path(path_type=Path, dir_okay=False))
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def profile_status(obj: dict[str, Any], path: Path, as_json: bool) -> None:
    """What the governing runtime profile says about one file (PATH relative to the workspace root)."""
    if not (obj["root"] / path).is_file():
        click.echo(f"groovyscope: {path} is not a file under {obj['root']}", err=True)
        sys.exit(2)
    socket_file, _ = daemon_socket_for(obj)
    status = engine_call(
        socket_file, "groovyscope/profile", {"root": str(obj["root"]), "uri": to_uris(obj["root"], [path])[0]}
    )
    if as_json:
        click.echo(compact_json(status))
        return
    if not status.get("governed"):
        click.echo(f"{shown_path(path.resolve(), obj['root'])}: not governed by a runtime profile")
        return
    for key, value in status.items():
        if key != "governed" and value not in ("", [], {}, None):
            click.echo(f"{key}: {value}")


@main.command("trust")
@click.option("--revoke", is_flag=True, help="Stop trusting the workspace.")
@click.option("--list", "list_all", is_flag=True, help="List the trusted workspaces.")
@click.pass_obj
def trust_command(obj: dict[str, Any], revoke: bool, list_all: bool) -> None:
    """Trust this workspace: its .groovyscope.yaml may then set workspace.trust (AST transformations from the
    workspace classpath), jdk.home and CodeNarc rule sets, and its runtime profiles may load emulation jars."""
    home = Path.home()
    if list_all:
        for root in trust.trusted_roots(home):
            click.echo(root)
        return
    trust.set_trusted(obj["root"], home, trusted=not revoke)
    state = "no longer trusted" if revoke else "trusted"
    click.echo(f"{obj['root']} is {state}; run `groovyscope daemon restart` if a daemon is serving it")


@main.group()
def baseline() -> None:
    """Record and compare the contract surface of files governed by a runtime profile."""


def contracts_now(obj: dict[str, Any]) -> dict[str, dict[str, str]]:
    socket_file, _ = daemon_socket_for(obj)
    files = engine_call(socket_file, "groovyscope/contracts", {"root": str(obj["root"])}).get("files", {})
    return {str(path): {str(k): str(v) for k, v in items.items()} for path, items in files.items()}


def baseline_file(root: Path) -> Path:
    return root / BASELINE_PATH


@baseline.command("save")
@click.pass_obj
def baseline_save(obj: dict[str, Any]) -> None:
    """Record parameter names and types, output keys, frozen methods, exception texts and test seams."""
    current = contracts_now(obj)
    target = baseline_file(obj["root"])
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps({"files": current}, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    items = sum(len(v) for v in current.values())
    click.echo(f"{items} contract item(s) in {len(current)} file(s) recorded in {shown_path(target, obj['root'])}")


@baseline.command("check")
@click.pass_obj
def baseline_check(obj: dict[str, Any]) -> None:
    """Compare the contract surface with the recorded baseline; exit 1 on anything removed or changed."""
    target = baseline_file(obj["root"])
    if not target.is_file():
        click.echo(f"groovyscope: no baseline at {shown_path(target, obj['root'])}; run `groovyscope baseline save`")
        sys.exit(1)
    recorded = json.loads(target.read_text(encoding="utf-8")).get("files", {})
    drift = profiles.contract_drift(recorded, contracts_now(obj))
    for line in drift:
        click.echo(line)
    click.echo(f"{len(drift)} contract change(s)" if drift else "contract unchanged")
    if drift:
        sys.exit(1)


@main.group()
def daemon() -> None:
    """Manage the warm engine daemon for this workspace."""


@daemon.command("start")
@click.pass_obj
def daemon_start(obj: dict[str, Any]) -> None:
    """Start the daemon (or report the one already running). Asking for a start clears the crash-loop guard."""
    daemon_lifecycle.forget_restarts(daemon_paths(obj["root"])[1])
    socket_file, _ = daemon_socket_for(obj)
    status = daemon_lifecycle.probe(socket_file) or {}
    click.echo(f"daemon running on {socket_file} (engine {status.get('engine', '?')})")


@daemon.command("restart")
@click.pass_obj
def daemon_restart(obj: dict[str, Any]) -> None:
    """Stop the daemon and start it again with the current configuration (open editor sessions reconnect)."""
    socket_file, log_file, _ = daemon_paths(obj["root"])
    try:
        daemon_lifecycle.stop(socket_file)
    except (OSError, RuntimeError) as exc:
        click.echo(f"groovyscope: {exc}", err=True)
        sys.exit(1)
    daemon_lifecycle.forget_restarts(log_file)
    socket_file, _ = daemon_socket_for(obj)
    status = daemon_lifecycle.probe(socket_file) or {}
    click.echo(f"daemon restarted on {socket_file} (engine {status.get('engine', '?')})")


@daemon.command("stop")
@click.pass_obj
def daemon_stop(obj: dict[str, Any]) -> None:
    """Ask the daemon to exit."""
    socket_file, log_file, _ = daemon_paths(obj["root"])
    try:
        stopped = daemon_lifecycle.stop(socket_file)
    except (OSError, RuntimeError) as exc:
        click.echo(f"groovyscope: {exc}", err=True)
        sys.exit(1)
    daemon_lifecycle.forget_restarts(log_file)
    click.echo("daemon stopped" if stopped else "daemon was not running")


@daemon.command("status")
@click.pass_obj
def daemon_status(obj: dict[str, Any]) -> None:
    """Show whether the daemon runs and what it knows."""
    details = daemon_lifecycle.info(obj["root"], dict(os.environ))
    rows = [("socket", details["socket"]), ("running", "yes" if details["running"] else "no"), ("log", details["log"])]
    for key, value in (details["status"] or {}).items():
        if key == "memory":
            rows.append((key, daemon_lifecycle.memory_summary(details["status"]) or str(value)))
        else:
            rows.append((key, str(value)))
    click.echo(table(rows))
    warning = daemon_lifecycle.memory_warning(details["status"])
    if warning:
        click.echo(f"warning: {warning}")
    if details["outOfMemory"]:
        click.echo(f"warning: {daemon_lifecycle.out_of_memory_advice(details['log'])}")
    if details["heap"]:
        click.echo(f"note: the last start used a {details['heap']} heap (grown after an out-of-memory exit)")
    if details["restarts"] > 1:
        click.echo(f"note: the daemon was started {details['restarts']} times in the last ten minutes")
    if not details["running"]:
        sys.exit(3)


if __name__ == "__main__":
    main()


@main.group("checks")
def checks_group() -> None:
    """The owner's Kiro checks (docs/kiro-checks.md): a throwaway workspace and a report to paste back."""


_CHECKS_RUNNER: checks.Runner = checks.default_runner


@checks_group.command("prepare")
@click.argument("directory", required=False, type=click.Path(path_type=Path, file_okay=False))
@click.option("--force", is_flag=True, help="Replace a workspace an earlier `checks prepare` made (never another).")
@click.pass_obj
def checks_prepare(obj: dict[str, Any], directory: Path | None, *, force: bool) -> None:
    """Make the workspace every Kiro check runs in (default: <tmp>/gs-check): the CodeNarc corpus, a build.gradle,
    a Jenkinsfile, git history with a `change` branch to review, the project's Kiro LSP entry and trust. Your
    shell stays where it is."""
    target = (directory or Path(tempfile.gettempdir()) / checks.DEFAULT_DIRECTORY).resolve()
    if target.exists():
        if not force or not checks.is_checks_workspace(target):
            reason = (
                "it was not made by `groovyscope checks prepare`, so it is left alone"
                if force
                else "pass --force to replace it"
            )
            click.echo(f"{target} exists; {reason}", err=True)
            sys.exit(1)
        shutil.rmtree(target)
    corpus = unpack_benchmark(dict(os.environ))
    if corpus is None:
        sys.exit(1)
    try:
        done = checks.prepare(target, corpus, _CHECKS_RUNNER)
    except (OSError, subprocess.CalledProcessError) as exc:
        click.echo(f"groovyscope checks prepare: {exc}", err=True)
        sys.exit(1)
    for line in done:
        click.echo(line)
    run_kiro_merge({**obj, "root": target, "config_path": None}, force=False, backup=False, dry_run=False)
    trust.set_trusted(target, Path.home(), trusted=True)
    click.echo(f"{target} is trusted")
    for line in checks.next_steps(target):
        click.echo(line)


def hook_seconds(root: Path) -> float:
    """How long `groovyscope hook` takes to start and answer a Stop event in ``root``: what Kiro waits for after
    every write and at the end of every turn."""
    command = [sys.executable, "-c", "from groovyscope.cli import main; main()", "--root", str(root), "hook"]
    started = time.monotonic()
    subprocess.run([*command, "--event", "stop"], input="{}", capture_output=True, text=True, check=False)
    return time.monotonic() - started


_HOOK_TIMER: Callable[[Path], float] = hook_seconds


@checks_group.command("report")
@click.pass_obj
def checks_report(obj: dict[str, Any]) -> None:
    """Print the block to paste back after the Kiro checks: versions, the doctor lines (the MCP client and whether
    it can be asked included), the hook start-up time, and the result table to fill in."""
    manifest = read_manifest() or {}
    facts = {
        "groovyscope": __version__,
        "engine": str(manifest.get("version", "not bundled")),
        "codenarc": str(manifest.get("codenarc", "unknown")),
        "workspace": str(obj["root"]),
        "hook start-up": f"{_HOOK_TIMER(obj['root']):.2f} s (Kiro waits this long after every write and turn)",
    }
    rows = build_report(obj["root"], obj["config_path"], dict(os.environ), kiro=_KIRO())
    for line in checks.report(facts, rows):
        click.echo(line)
