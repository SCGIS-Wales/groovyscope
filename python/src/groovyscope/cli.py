"""The ``groovyscope`` command line. Thin click wrappers over pure functions in the sibling modules."""

from __future__ import annotations

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

from groovyscope import __version__, relay, rpc
from groovyscope import daemon as daemon_lifecycle
from groovyscope.config import dump_yaml, load_config
from groovyscope.doctor import build_report, has_failures
from groovyscope.edits import apply_edits, changed_line_count
from groovyscope.jar import locate_jar, read_manifest
from groovyscope.java import JdkInfo, discover_jdk
from groovyscope.kiro import KIRO_INIT_WARNING, apply_changes, plan_changes, unified_diff
from groovyscope.launch import MIN_JDK_FOR_AUTO_CDS, build_engine_command
from groovyscope.render import compact_json, table

EXIT_NOT_IMPLEMENTED = 3
LOG_ENV = "GROOVYSCOPE_LOG"
CONFIG_ENV = "GROOVYSCOPE_CONFIG_JSON"
LEVEL_MARKS = {"ok": "ok  ", "warn": "warn", "fail": "FAIL", "info": "info"}

ExecFn = Callable[[str, Sequence[str]], Any]


def configure_logging(environ: dict[str, str] | None = None) -> None:
    env = os.environ if environ is None else environ
    level_name = env.get(LOG_ENV, "warning").upper()
    logging.basicConfig(level=getattr(logging, level_name, logging.WARNING), stream=sys.stderr, format="%(message)s")


def not_implemented(feature: str, stage: str) -> None:
    click.echo(f"groovyscope: {feature} is not implemented yet (planned for {stage}); see docs/roadmap.md", err=True)
    sys.exit(EXIT_NOT_IMPLEMENTED)


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
@click.pass_context
def main(ctx: click.Context, config_path: Path | None, root: Path | None) -> None:
    """Groovy 5/6 code intelligence for AI coding agents."""
    configure_logging()
    ctx.obj = {"config_path": config_path, "root": (root or Path.cwd()).resolve()}


@main.command()
def version() -> None:
    """Print the package and bundled engine versions."""
    manifest = read_manifest()
    engine = manifest.get("version", "unknown") if manifest else "not bundled"
    click.echo(f"groovyscope {__version__}")
    click.echo(f"engine {engine}")


@main.command()
@click.pass_obj
def doctor(obj: dict[str, Any]) -> None:
    """Check the JDK, engine JAR, configuration and Kiro files; exit 1 on hard failures."""
    report = build_report(obj["root"], obj["config_path"], dict(os.environ))
    click.echo(table([(LEVEL_MARKS[level], name, message) for level, name, message in report]))
    if has_failures(report):
        sys.exit(1)


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
    """Install or repair the Kiro CLI integration files in the workspace."""


def run_kiro_merge(root: Path, *, force: bool, backup: bool, dry_run: bool) -> None:
    try:
        changes = plan_changes(root, force=force)
    except ValueError as exc:
        click.echo(str(exc), err=True)
        sys.exit(1)
    for change in changes:
        click.echo(f"{change['action']:9} {change['path']}")
        if change["action"] != "unchanged":
            click.echo(unified_diff(change), nl=False)
    if dry_run:
        click.echo("dry run: nothing written")
    else:
        written = apply_changes(root, changes, backup=backup)
        click.echo(f"{len(written)} file(s) written" if written else "nothing to do")
    click.echo(f"note: {KIRO_INIT_WARNING}", err=True)
    click.echo("note: the MCP server lands in a later stage; `groovyscope mcp` currently exits 3.", err=True)
    click.echo("next: restart Kiro CLI, then run /code status (expect: groovyscope (groovy) - initialized)")


@kiro.command("init")
@click.option("--force", is_flag=True, help="Replace existing files instead of merging into them.")
@click.option("--no-backup", is_flag=True, help="Do not write <file>.bak before overwriting.")
@click.option("--dry-run", is_flag=True, help="Show the diff without writing anything.")
@click.pass_obj
def kiro_init(obj: dict[str, Any], force: bool, no_backup: bool, dry_run: bool) -> None:
    """Write lsp.json, mcp.json, the groovy agent and the steering file (merging, never clobbering)."""
    run_kiro_merge(obj["root"], force=force, backup=not no_backup, dry_run=dry_run)


@kiro.command("repair")
@click.option("--dry-run", is_flag=True, help="Show the diff without writing anything.")
@click.pass_obj
def kiro_repair(obj: dict[str, Any], dry_run: bool) -> None:
    """Re-merge groovyscope's entries after `/code init` or a manual edit removed them."""
    run_kiro_merge(obj["root"], force=False, backup=True, dry_run=dry_run)


def cds_archive_for(
    effective: dict[str, Any], jdk: JdkInfo, jar_sha256: str | None, environ: dict[str, str]
) -> Path | None:
    """The CDS archive to use, or None when disabled, unsupported by the JDK, or the JAR is unverified."""
    if not effective["daemon"].get("cds", True) or jdk["version"] < MIN_JDK_FOR_AUTO_CDS or not jar_sha256:
        return None
    return daemon_lifecycle.cache_dir(environ) / "cds" / f"engine-{jar_sha256[:16]}.jsa"


def resolve_engine(obj: dict[str, Any]) -> tuple[list[str], dict[str, Any]]:
    """Resolve configuration, JDK and JAR; exit 1 with a message on any failure."""
    try:
        effective = load_config(obj["root"], obj["config_path"], dict(os.environ))
    except (ValueError, OSError) as exc:
        click.echo(str(exc), err=True)
        sys.exit(1)
    minimum = int(effective["jdk"]["minimum"])
    jdk = discover_jdk(effective["jdk"].get("home"), dict(os.environ), minimum=minimum)
    if jdk is None or jdk["version"] < minimum:
        click.echo("groovyscope: no suitable JDK found; run `groovyscope doctor`", err=True)
        sys.exit(1)
    jar = locate_jar(dict(os.environ))
    if jar["error"] or jar["path"] is None:
        click.echo(f"groovyscope: {jar['error']}", err=True)
        sys.exit(1)
    archive = cds_archive_for(effective, jdk, jar["actual_sha256"], dict(os.environ))
    if archive is not None:
        archive.parent.mkdir(parents=True, exist_ok=True)
    command = build_engine_command(Path(jdk["home"]), Path(jar["path"]), effective, [], cds_archive=archive)
    return (command, effective)


def bundled_config(relative: str) -> Path:
    """A configuration file shipped inside the package (rulesets, formatter settings)."""
    return Path(str(resources.files("groovyscope").joinpath("_templates/config").joinpath(relative)))


def engine_view(obj: dict[str, Any], effective: dict[str, Any]) -> dict[str, Any]:
    """The effective configuration with bundled defaults resolved to real paths, as the engine needs it."""
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
    return {**effective, "lint": lint, "root": str(obj["root"])}


def hand_over_config(obj: dict[str, Any], effective: dict[str, Any]) -> None:
    """The engine reads the validated, effective configuration from the environment (see EngineConfig)."""
    os.environ[CONFIG_ENV] = json.dumps(engine_view(obj, effective), sort_keys=True)


def daemon_paths(root: Path) -> tuple[Path, Path, Path]:
    """``(socket, log, cache)`` for a workspace root."""
    environ = dict(os.environ)
    return (
        daemon_lifecycle.socket_path(root, environ),
        daemon_lifecycle.log_path(root, environ),
        daemon_lifecycle.cache_dir(environ),
    )


def run_through_daemon(root: Path, command: list[str], effective: dict[str, Any]) -> int | None:
    """Start the daemon if needed and relay stdio to it. None when that is impossible (caller falls back)."""
    try:
        socket_file, log_file, cache = daemon_paths(root)
        daemon_command = daemon_lifecycle.daemon_command(
            command, socket_file, int(effective["daemon"]["idleTimeoutMinutes"]), cache
        )
        daemon_lifecycle.ensure_running(daemon_command, socket_file, log_file, dict(os.environ))
        return _RELAY(socket_file)
    except (OSError, RuntimeError) as exc:
        click.echo(f"groovyscope: daemon unavailable ({exc}); running the engine directly", err=True)
        return None


def exec_process(path: str, argv: Sequence[str]) -> None:
    """Replace this process with the engine (never returns on success)."""
    os.execv(path, list(argv))  # noqa: S606 - deliberate: the CLI becomes the engine process


_EXEC: ExecFn = exec_process
_RELAY: Callable[[Path], int] = relay.main
_RPC: Callable[..., Any] = rpc.request
RPC_TIMEOUT_SECONDS = 600.0
SEVERITY_NAMES = {1: "error", 2: "warning", 3: "info", 4: "hint"}


def daemon_socket_for(obj: dict[str, Any]) -> tuple[Path, dict[str, Any]]:
    """Make sure the workspace daemon runs and return its socket with the effective configuration."""
    command, effective = resolve_engine(obj)
    hand_over_config(obj, effective)
    socket_file, log_file, cache = daemon_paths(obj["root"])
    daemon_command = daemon_lifecycle.daemon_command(
        command, socket_file, int(effective["daemon"]["idleTimeoutMinutes"]), cache
    )
    try:
        daemon_lifecycle.ensure_running(daemon_command, socket_file, log_file, dict(os.environ))
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
        code = run_through_daemon(obj["root"], command, effective)
        if code is not None:
            sys.exit(code)
    _EXEC(command[0], [*command, "lsp", "--stdio", "--cache-dir", str(daemon_paths(obj["root"])[2])])


@main.command()
@click.argument("paths", nargs=-1, type=click.Path(path_type=Path))
@click.option(
    "--profile", type=click.Choice(["default", "jenkins"]), default=None, help="Rule set profile (default: by file)."
)
@click.option("--include", default="lint,compile,typecheck", show_default=True, help="Comma-separated analyses.")
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def check(obj: dict[str, Any], paths: tuple[Path, ...], profile: str | None, include: str, as_json: bool) -> None:
    """Lint, compile and type-check Groovy files (the whole workspace when no PATHS are given)."""
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
    if as_json:
        click.echo(compact_json({"findings": rows, "summary": summary}))
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
    for uri, edits in sorted(result.get("edits", {}).items()):
        path = uri_to_path(uri)
        before = path.read_text(encoding="utf-8")
        after = apply_edits(before, edits)
        if apply_changes:
            path.write_text(after, encoding="utf-8")
        try:
            shown = path.relative_to(obj["root"]).as_posix()
        except ValueError:
            shown = path.as_posix()
        rows.append({"path": shown, "lines": changed_line_count(before, after), "applied": apply_changes})
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
def mcp() -> None:
    """Run the MCP server (planned)."""
    not_implemented("the MCP server", "Stage 5")


@main.group()
def daemon() -> None:
    """Manage the warm engine daemon for this workspace."""


@daemon.command("start")
@click.pass_obj
def daemon_start(obj: dict[str, Any]) -> None:
    """Start the daemon (or report the one already running)."""
    command, effective = resolve_engine(obj)
    hand_over_config(obj, effective)
    socket_file, log_file, cache = daemon_paths(obj["root"])
    daemon_command = daemon_lifecycle.daemon_command(
        command, socket_file, int(effective["daemon"]["idleTimeoutMinutes"]), cache
    )
    try:
        status = daemon_lifecycle.ensure_running(daemon_command, socket_file, log_file, dict(os.environ))
    except (OSError, RuntimeError) as exc:
        click.echo(f"groovyscope: {exc}", err=True)
        sys.exit(1)
    click.echo(f"daemon running on {socket_file} (engine {status.get('engine', '?')})")


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
