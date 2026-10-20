"""The engine as a server: ``lsp``, ``mcp``, the daemon's lifecycle and its logs."""

from __future__ import annotations

import contextlib
import os
import sys
from pathlib import Path
from typing import Any

import click

from groovyscope import daemon as daemon_lifecycle
from groovyscope import (
    diagnostics,
)
from groovyscope.cli import common
from groovyscope.render import table


@common.main.command()
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
    log_file = common.daemon_paths(obj["root"])[1]
    click.echo(f"== {log_file}", err=True)  # command output: which file follows
    for line in diagnostics.tail(log_file, lines):
        click.echo(line)
    if follow_log:
        with contextlib.suppress(KeyboardInterrupt):
            diagnostics.follow(log_file, click.echo)


@common.main.command()
@click.option("--stdio", is_flag=True, help="Serve LSP over stdin/stdout (the only transport today).")
@click.pass_obj
def lsp(obj: dict[str, Any], stdio: bool) -> None:
    """Run the engine's Language Server for Kiro CLI or an editor."""
    if not stdio:
        common.LOG.error("groovyscope lsp: pass --stdio (Kiro's lsp.json does)")
        sys.exit(2)
    command, effective = common.resolve_engine(obj)
    common.hand_over_config(obj, effective)
    if effective["daemon"]["enabled"]:
        code = common.run_through_daemon(obj, command, effective)
        if code is not None:
            sys.exit(code)
    # The engine replaces this process, which only now runs no other thread: its environment is the engine's.
    command = daemon_lifecycle.direct_heap(command, common.daemon_paths(obj["root"])[1], effective["daemon"])
    engine_env = common.engine_environ(obj, os.environ)
    if common.CONFIG_ENV in engine_env:
        os.environ[common.CONFIG_ENV] = engine_env[common.CONFIG_ENV]
    else:
        os.environ.pop(common.CONFIG_ENV, None)
    common._EXEC(
        command[0],
        [
            *command,
            "lsp",
            "--stdio",
            "--cache-dir",
            str(common.daemon_paths(obj["root"])[2]),
            *common.config_arguments(obj),
        ],
    )


@common.main.command()
@click.pass_obj
def mcp(obj: dict[str, Any]) -> None:
    """Serve groovyscope's MCP tools on stdio (what .kiro/settings/mcp.json runs)."""
    socket_file, effective = common.daemon_socket_for(obj)

    def reconnect() -> Path:
        try:
            return common.start_daemon_for(obj)[0]
        except (OSError, RuntimeError, SystemExit) as exc:
            # The agent reads this: the refusal's own reason says what to change.
            msg = f"the engine daemon could not be restarted: {exc}; run `groovyscope doctor`"
            raise ConnectionError(msg) from exc

    settings = common.tool_settings(effective, common.classpath_settings(obj, effective))
    sys.exit(
        common._MCP_SERVE(
            obj["root"], socket_file, settings, dict(os.environ), reconnect, configuration=common.config_source(obj)
        )
    )


@common.main.group()
def daemon() -> None:
    """Manage the warm engine daemon for this workspace."""


@daemon.command("start")
@click.pass_obj
def daemon_start(obj: dict[str, Any]) -> None:
    """Start the daemon (or report the one already running). Asking for a start clears the crash-loop guard."""
    daemon_lifecycle.forget_restarts(common.daemon_paths(obj["root"])[1])
    socket_file, _ = common.daemon_socket_for(obj)
    status = daemon_lifecycle.probe(socket_file) or {}
    click.echo(f"daemon running on {socket_file} (engine {status.get('engine', '?')})")


@daemon.command("restart")
@click.pass_obj
def daemon_restart(obj: dict[str, Any]) -> None:
    """Stop the daemon and start it again with the current configuration (open editor sessions reconnect)."""
    socket_file, log_file, _ = common.daemon_paths(obj["root"])
    try:
        daemon_lifecycle.stop(socket_file)
    except (OSError, RuntimeError) as exc:
        common.LOG.error(f"groovyscope: {exc}")
        sys.exit(1)
    daemon_lifecycle.forget_restarts(log_file)
    socket_file, _ = common.daemon_socket_for(obj)
    status = daemon_lifecycle.probe(socket_file) or {}
    click.echo(f"daemon restarted on {socket_file} (engine {status.get('engine', '?')})")


@daemon.command("stop")
@click.pass_obj
def daemon_stop(obj: dict[str, Any]) -> None:
    """Ask the daemon to exit."""
    socket_file, log_file, _ = common.daemon_paths(obj["root"])
    try:
        stopped = daemon_lifecycle.stop(socket_file)
    except (OSError, RuntimeError) as exc:
        common.LOG.error(f"groovyscope: {exc}")
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
