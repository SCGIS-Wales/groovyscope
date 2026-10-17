"""The ``groovyscope`` command line. Thin click wrappers over pure functions in the sibling modules."""

from __future__ import annotations

import json
import logging
import os
import sys
from collections.abc import Callable, Sequence
from pathlib import Path
from typing import Any

import click

from groovyscope import __version__
from groovyscope.config import dump_yaml, load_config
from groovyscope.doctor import build_report, has_failures
from groovyscope.jar import locate_jar, read_manifest
from groovyscope.java import discover_jdk
from groovyscope.kiro import KIRO_INIT_WARNING, apply_changes, plan_changes, unified_diff
from groovyscope.launch import build_engine_command
from groovyscope.render import table

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
    return (build_engine_command(Path(jdk["home"]), Path(jar["path"]), effective, []), effective)


def exec_process(path: str, argv: Sequence[str]) -> None:
    """Replace this process with the engine (never returns on success)."""
    os.execv(path, list(argv))  # noqa: S606 - deliberate: the CLI becomes the engine process


_EXEC: ExecFn = exec_process


@main.command()
@click.option("--stdio", is_flag=True, help="Serve LSP over stdin/stdout (the only transport today).")
@click.pass_obj
def lsp(obj: dict[str, Any], stdio: bool) -> None:
    """Run the engine's Language Server for Kiro CLI or an editor."""
    if not stdio:
        click.echo("groovyscope lsp: pass --stdio (Kiro's lsp.json does)", err=True)
        sys.exit(2)
    command, effective = resolve_engine(obj)
    if effective["daemon"]["enabled"]:
        logging.getLogger(__name__).info("daemon mode arrives in a later stage; running the engine directly")
    # The engine reads the validated, effective configuration from the environment (see EngineConfig).
    os.environ[CONFIG_ENV] = json.dumps({**effective, "root": str(obj["root"])}, sort_keys=True)
    _EXEC(command[0], [*command, "lsp", "--stdio"])


@main.command()
def mcp() -> None:
    """Run the MCP server (planned)."""
    not_implemented("the MCP server", "Stage 5")


@main.group()
def daemon() -> None:
    """Manage the warm engine daemon (planned)."""


@daemon.command("start")
def daemon_start() -> None:
    """Start the daemon for this workspace (planned)."""
    not_implemented("the daemon", "Stage 2")


@daemon.command("stop")
def daemon_stop() -> None:
    """Stop the daemon for this workspace (planned)."""
    not_implemented("the daemon", "Stage 2")


@daemon.command("status")
def daemon_status() -> None:
    """Show daemon status (planned)."""
    not_implemented("the daemon", "Stage 2")


if __name__ == "__main__":
    main()
