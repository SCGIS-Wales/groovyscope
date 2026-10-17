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

from groovyscope import __version__, mcp_server, practices, provision, relay, rpc
from groovyscope import daemon as daemon_lifecycle
from groovyscope import fetch as fetch_engine
from groovyscope.config import dump_yaml, load_config
from groovyscope.doctor import build_report, has_failures
from groovyscope.edits import apply_edits, changed_line_count
from groovyscope.jar import locate_jar, read_manifest
from groovyscope.java import JdkInfo, discover_jdk
from groovyscope.kiro import KIRO_INIT_WARNING, apply_changes, plan_changes, unified_diff
from groovyscope.launch import MIN_JDK_FOR_AUTO_CDS, build_engine_command
from groovyscope.render import compact_json, table

LOG_ENV = "GROOVYSCOPE_LOG"
CONFIG_ENV = "GROOVYSCOPE_CONFIG_JSON"
LEVEL_MARKS = {"ok": "ok  ", "warn": "warn", "fail": "FAIL", "info": "info"}

ExecFn = Callable[[str, Sequence[str]], Any]


def configure_logging(environ: dict[str, str] | None = None) -> None:
    env = os.environ if environ is None else environ
    level_name = env.get(LOG_ENV, "warning").upper()
    logging.basicConfig(level=getattr(logging, level_name, logging.WARNING), stream=sys.stderr, format="%(message)s")


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


def resolve_jdk(effective: dict[str, Any], environ: dict[str, str]) -> JdkInfo | None:
    """A JDK meeting ``jdk.minimum``; with ``jdk.autoProvision`` on, Temurin is downloaded when none is found."""
    minimum = int(effective["jdk"]["minimum"])
    provisioned = provision.provisioned_homes(provision.jdk_dir(environ))
    jdk = discover_jdk(effective["jdk"].get("home"), environ, minimum=minimum, provisioned=provisioned)
    if jdk is not None and jdk["version"] >= minimum:
        return jdk
    if not effective["jdk"].get("autoProvision"):
        return None
    feature = max(minimum, provision.DEFAULT_FEATURE)
    click.echo(
        f"groovyscope: no JDK {minimum}+ found; jdk.autoProvision is on, downloading Temurin {feature}", err=True
    )
    try:
        installed = provision.provision_jdk(provision.jdk_dir(environ), feature=feature)
    except (OSError, ValueError, KeyError) as exc:
        click.echo(f"groovyscope: JDK provisioning failed: {exc}", err=True)
        return None
    return {"home": installed["home"], "version": installed["version"], "source": "provisioned"}


def resolve_engine(obj: dict[str, Any]) -> tuple[list[str], dict[str, Any]]:
    """Resolve configuration, JDK and JAR; exit 1 with a message on any failure."""
    try:
        effective = load_config(obj["root"], obj["config_path"], dict(os.environ))
    except (ValueError, OSError) as exc:
        click.echo(str(exc), err=True)
        sys.exit(1)
    jdk = resolve_jdk(effective, dict(os.environ))
    if jdk is None:
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
_MCP_SERVE: Callable[..., int] = mcp_server.serve
RPC_TIMEOUT_SECONDS = 600.0
SEVERITY_NAMES = {1: "error", 2: "warning", 3: "info", 4: "hint"}
POSITION_PARTS = 3  # path:line:col


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
    for uri, edits in sorted(result.get("edits", {}).items()):
        path = uri_to_path(uri)
        before = path.read_text(encoding="utf-8")
        after = apply_edits(before, edits)
        if apply_changes:
            path.write_text(after, encoding="utf-8")
        rows.append(
            {
                "path": shown_path(path, obj["root"]),
                "codes": result.get("applied", {}).get(uri, []),
                "lines": changed_line_count(before, after),
                "applied": apply_changes,
            }
        )
    rejected = {shown_path(uri_to_path(uri), obj["root"]): why for uri, why in result.get("rejected", {}).items()}
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
    sys.exit(_MCP_SERVE(obj["root"], socket_file, effective["output"], dict(os.environ)))


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
def jdk_install(feature: int) -> None:
    """Download the latest Temurin JDK of a feature release from Adoptium, verify it, unpack it into the cache."""
    environ = dict(os.environ)
    try:
        installed = provision.provision_jdk(provision.jdk_dir(environ), feature=feature)
    except (OSError, ValueError, KeyError) as exc:
        click.echo(f"groovyscope: JDK provisioning failed: {exc}", err=True)
        sys.exit(1)
    click.echo(f"installed Temurin {installed['release']} at {installed['home']}")
    click.echo("groovyscope will use it when no other JDK meets jdk.minimum; `groovyscope doctor` shows which wins")


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
