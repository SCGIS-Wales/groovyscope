"""The ``groovyscope`` command line. Thin click wrappers over pure functions in the sibling modules."""

from __future__ import annotations

import contextlib
import hashlib
import json
import logging
import os
import sys
import urllib.parse
from collections.abc import Callable, Sequence
from importlib import resources
from pathlib import Path
from typing import Any

import click

from groovyscope import (
    __version__,
    diagnostics,
    kiro_agent,
    mcp_server,
    practices,
    profiles,
    provision,
    relay,
    rpc,
    trust,
)
from groovyscope import daemon as daemon_lifecycle
from groovyscope import fetch as fetch_engine
from groovyscope.config import dump_yaml, load_config, load_config_with_notes
from groovyscope.doctor import build_report, has_failures
from groovyscope.edits import changed_line_count, file_edits, write_document
from groovyscope.jar import locate_jar, read_manifest
from groovyscope.java import JdkInfo, discover
from groovyscope.kiro import (
    BACKUP_SUFFIX,
    KIRO_INIT_WARNING,
    apply_changes,
    has_global_setup,
    kiro_home,
    plan_changes,
    unified_diff,
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


@main.command()
def version() -> None:
    """Print the package and bundled engine versions."""
    manifest = read_manifest()
    engine = manifest.get("version", "unknown") if manifest else "not bundled"
    click.echo(f"groovyscope {__version__}")
    click.echo(f"engine {engine}")


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
            versions={"groovyscope": __version__, "engine": (manifest or {}).get("version")},
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
    try:
        effective = load_config(obj["root"], obj["config_path"], dict(os.environ))
    except (ValueError, OSError):
        return ""
    found = profiles.discover(obj["root"], effective.get("profiles", {}), Path.home())
    if global_scope:
        # The global steering applies to every project, so only profiles kept outside this repository count.
        root = obj["root"].resolve()
        outside = [p for p in found["profiles"] if not Path(p["path"]).resolve().is_relative_to(root)]
        found = {**found, "profiles": outside}
        return profiles.guidance(found, None)
    return profiles.guidance(found, obj["root"])


def run_kiro_merge(
    obj: dict[str, Any],
    *,
    force: bool,
    backup: bool,
    dry_run: bool,
    global_scope: bool = False,
    full: bool = False,
    agent_format: str = "auto",
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
        click.echo(f"{change['action']:9} {change['path']}")
        if change.get("note"):
            click.echo(f"          note: {change.get('note')}")
        if change["action"] != "unchanged":
            click.echo(unified_diff(change), nl=False)
    if dry_run:
        click.echo("dry run: nothing written")
    else:
        written = apply_changes(base, changes, backup=backup)
        click.echo(f"{len(written)} file(s) written" if written else "nothing to do")
        if written and backup and any(c["before"] is not None and c["action"] == "update" for c in changes):
            click.echo(f"originals kept as <file>{BACKUP_SUFFIX}")
    if global_scope:
        click.echo(
            "next: in each Groovy project run `groovyscope kiro init` once; Kiro CLI reads LSP servers only from "
            "the project's .kiro/settings/lsp.json"
        )
        return
    click.echo(f"note: {KIRO_INIT_WARNING}", err=True)
    click.echo("next: restart Kiro CLI, then run /code status (expect: groovyscope (groovy) - initialized)")


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
    )


@kiro.command("repair")
@click.option("--global", "global_scope", is_flag=True, help=GLOBAL_HELP)
@click.option("--full", is_flag=True, help=FULL_HELP)
@click.option("--agent-format", type=FORMAT_CHOICE, default="auto", show_default=True, help=FORMAT_HELP)
@click.option("--dry-run", is_flag=True, help="Show the diff without writing anything.")
@click.pass_obj
def kiro_repair(obj: dict[str, Any], *, global_scope: bool, full: bool, agent_format: str, dry_run: bool) -> None:
    """Re-merge groovyscope's entries after `/code init`, a manual edit or a Kiro upgrade changed them."""
    run_kiro_merge(
        obj,
        force=False,
        backup=True,
        dry_run=dry_run,
        global_scope=global_scope,
        full=full,
        agent_format=agent_format,
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
    command = build_engine_command(Path(jdk["home"]), Path(jar["path"]), effective, [], cds_archive=archive)
    obj["engine_version"] = jar["version"]
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
    return {**effective, "lint": lint, "root": str(obj["root"]), "profiles": list(runtime_profiles)}


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


def warn_if_stale(status: dict[str, Any]) -> None:
    """A daemon started with another configuration keeps serving it; say so instead of failing silently."""
    running = status.get("configHash")
    if isinstance(running, str) and running and running != config_hash():
        click.echo(
            "groovyscope: the daemon was started with a different configuration (.groovyscope.yaml, profiles or "
            "environment changed); run `groovyscope daemon restart` to apply it",
            err=True,
        )


def ensure_daemon(obj: dict[str, Any], command: list[str], effective: dict[str, Any]) -> tuple[Path, dict[str, Any]]:
    """Start the workspace daemon when needed (replacing one from another engine version). Raises on failure."""
    socket_file, log_file, cache = daemon_paths(obj["root"])
    daemon_command = daemon_lifecycle.daemon_command(
        command, socket_file, int(effective["daemon"]["idleTimeoutMinutes"]), cache
    )
    status = daemon_lifecycle.ensure_running(
        daemon_command, socket_file, log_file, dict(os.environ), expected_engine=obj.get("engine_version")
    )
    warn_if_stale(status)
    return (socket_file, status)


def run_through_daemon(obj: dict[str, Any], command: list[str], effective: dict[str, Any]) -> int | None:
    """Start the daemon if needed and relay stdio to it. None when that is impossible (caller falls back)."""
    try:
        socket_file, _ = ensure_daemon(obj, command, effective)
        return _RELAY(socket_file)
    except (OSError, RuntimeError) as exc:
        click.echo(f"groovyscope: daemon unavailable ({exc}); running the engine directly", err=True)
        return None


def exec_process(path: str, argv: Sequence[str]) -> None:
    """Replace this process with the engine (never returns on success)."""
    os.execv(path, list(argv))  # noqa: S606 - deliberate: the CLI becomes the engine process


_EXEC: ExecFn = exec_process
_KIRO: Callable[[], kiro_agent.KiroRunner | None] = kiro_agent.kiro_runner
_RELAY: Callable[[Path], int] = relay.main
_RPC: Callable[..., Any] = rpc.request
_MCP_SERVE: Callable[..., int] = mcp_server.serve
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


def engine_call(socket_file: Path, method: str, params: dict[str, Any]) -> dict[str, Any]:
    try:
        result = _RPC(socket_file, method, params, RPC_TIMEOUT_SECONDS)
    except (OSError, RuntimeError, ValueError) as exc:
        click.echo(f"groovyscope: {method} failed: {exc}", err=True)
        sys.exit(1)
    return dict(result) if isinstance(result, dict) else {}


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
@click.pass_obj
def check(
    obj: dict[str, Any],
    *,
    paths: tuple[Path, ...],
    profile: str | None,
    include: str,
    as_json: bool,
    output_format: str,
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
        if apply_changes:
            write_document(edit["path"], edit["after"])
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
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def fix(
    obj: dict[str, Any], paths: tuple[Path, ...], codes: tuple[str, ...], apply_changes: bool, as_json: bool
) -> None:
    """Apply groovyscope's safe fixes (practice findings and mechanical lint fixes); exits 1 when fixes remain."""
    socket_file, _ = daemon_socket_for(obj)
    params = {"root": str(obj["root"]), "uris": to_uris(obj["root"], paths), "codes": list(codes)}
    result = engine_call(socket_file, "groovyscope/fix", params)
    rows: list[dict[str, Any]] = []
    changed, refused = file_edits(result.get("edits", {}), obj["root"])
    applied_codes = {uri_to_path(uri): codes for uri, codes in result.get("applied", {}).items()}
    for edit in changed:
        if apply_changes:
            write_document(edit["path"], edit["after"])
        rows.append(
            {
                "path": shown_path(edit["path"], obj["root"]),
                "codes": applied_codes.get(edit["path"], []),
                "lines": changed_line_count(edit["before"], edit["after"]),
                "applied": apply_changes,
            }
        )
    rejected = {shown_path(uri_to_path(uri), obj["root"]): why for uri, why in result.get("rejected", {}).items()}
    rejected.update({shown_path(uri_to_path(uri), obj["root"]): "outside the workspace; refused" for uri in refused})
    if as_json:
        click.echo(compact_json({"files": rows, "rejected": rejected}))
    else:
        for row in rows:
            state = " (applied)" if row["applied"] else ""
            click.echo(f"{row['path']}: {', '.join(row['codes'])}{state}")
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

    sys.exit(_MCP_SERVE(obj["root"], socket_file, effective["output"], dict(os.environ), reconnect))


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
    """Start the daemon (or report the one already running)."""
    socket_file, _ = daemon_socket_for(obj)
    status = daemon_lifecycle.probe(socket_file) or {}
    click.echo(f"daemon running on {socket_file} (engine {status.get('engine', '?')})")


@daemon.command("restart")
@click.pass_obj
def daemon_restart(obj: dict[str, Any]) -> None:
    """Stop the daemon and start it again with the current configuration (open editor sessions reconnect)."""
    socket_file, _, _ = daemon_paths(obj["root"])
    try:
        daemon_lifecycle.stop(socket_file)
    except (OSError, RuntimeError) as exc:
        click.echo(f"groovyscope: {exc}", err=True)
        sys.exit(1)
    socket_file, _ = daemon_socket_for(obj)
    status = daemon_lifecycle.probe(socket_file) or {}
    click.echo(f"daemon restarted on {socket_file} (engine {status.get('engine', '?')})")


@daemon.command("stop")
@click.pass_obj
def daemon_stop(obj: dict[str, Any]) -> None:
    """Ask the daemon to exit."""
    socket_file, _, _ = daemon_paths(obj["root"])
    try:
        stopped = daemon_lifecycle.stop(socket_file)
    except (OSError, RuntimeError) as exc:
        click.echo(f"groovyscope: {exc}", err=True)
        sys.exit(1)
    click.echo("daemon stopped" if stopped else "daemon was not running")


@daemon.command("status")
@click.pass_obj
def daemon_status(obj: dict[str, Any]) -> None:
    """Show whether the daemon runs and what it knows."""
    details = daemon_lifecycle.info(obj["root"], dict(os.environ))
    rows = [("socket", details["socket"]), ("running", "yes" if details["running"] else "no"), ("log", details["log"])]
    for key, value in (details["status"] or {}).items():
        rows.append((key, str(value)))
    click.echo(table(rows))
    if not details["running"]:
        sys.exit(3)


if __name__ == "__main__":
    main()
