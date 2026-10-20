"""Kiro CLI integration: ``kiro init``, ``attach`` and ``repair``, and the bundled benchmark corpus."""

from __future__ import annotations

import os
import sys
from collections.abc import Sequence
from pathlib import Path
from typing import Any

import click

from groovyscope import (
    benchmark,
    checkup,
    kiro_agent,
    kiro_steer,
)
from groovyscope.cli import common
from groovyscope.kiro import (
    BACKUP_SUFFIX,
    KIRO_INIT_WARNING,
    apply_changes,
    backup_path,
    has_global_setup,
    kiro_home,
    plan_changes,
    unified_diff,
    write_atomically,
)


@common.main.group("benchmark")
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


@common.main.group()
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
            run=common._KIRO(),
        )
    except ValueError as exc:
        common.LOG.error(str(exc))
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
    common.LOG.warning(f"note: {KIRO_INIT_WARNING}")
    click.echo("next: restart Kiro CLI, then run /code status (expect: groovyscope (groovy) - initialized)")


def unpack_benchmark(environ: dict[str, str], *, refresh: bool = False, target: Path | None = None) -> Path | None:
    """Unpack the bundled benchmark corpus (CodeNarc src/main) into the user's home, or ``target``; one line of
    output, never an exception."""
    destination = target or benchmark.corpus_root(Path.home(), environ)
    try:
        result = benchmark.ensure(destination, refresh=refresh)
    except (OSError, ValueError) as exc:
        common.LOG.warning(f"benchmark corpus: could not unpack into {destination}: {exc}")
        return None
    if result is None:
        common.LOG.warning("benchmark corpus: this package bundles none")
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
    run = common._KIRO()
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
