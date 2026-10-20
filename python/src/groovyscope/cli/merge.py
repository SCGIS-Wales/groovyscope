"""``groovyscope merge``: the merge conflict tools from a terminal (status, show, resolve, review), answering what the
agent's merge_conflicts, resolve_conflicts and merge_review tools answer."""

from __future__ import annotations

import os
import sys
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any

import click

from groovyscope.cli import common
from groovyscope.config import load_config
from groovyscope.render import compact_json

NO_ENGINE = "the engine is not started for this command"


def no_engine(_method: str, _params: dict[str, Any]) -> Any:
    raise RuntimeError(NO_ENGINE)


def merge_tools(obj: dict[str, Any], *, engine: bool) -> dict[str, Callable[..., Any]]:
    """The tools for this workspace; with ``engine`` the daemon is started, so results can be parsed."""
    call: Callable[[str, dict[str, Any]], Any] = no_engine
    if engine:
        socket_file, effective = common.daemon_socket_for(obj)

        def call_engine(method: str, params: dict[str, Any]) -> Any:
            return common._RPC(socket_file, method, params, common.RPC_TIMEOUT_SECONDS)

        call = call_engine
    else:
        try:
            effective = load_config(obj["root"], obj["config_path"], dict(os.environ))
        except (ValueError, OSError) as exc:
            common.LOG.error(str(exc))
            sys.exit(1)
    from groovyscope import mcp_merge  # noqa: PLC0415 - the MCP SDK loads only for the commands that use it

    cap = int(effective["output"]["maxResults"])
    settings = common.tool_settings(effective)
    return mcp_merge.merge_tools(obj["root"], call, cap, settings, confirm_policy="never")


def fail_on_error(result: Mapping[str, Any]) -> None:
    if result.get("error"):
        click.echo(f"groovyscope: {result['error']}")
        for item in result.get("leftoverMarkers") or []:
            click.echo(f"  line {item['line']}: {item['marker']}")
        if result.get("advice"):
            click.echo(f"  {result['advice']}")
        sys.exit(1)


@common.main.group("merge")
def merge_group() -> None:
    """Merge conflicts: where they are, each hunk with a suggestion, one write per file, and a review for lost code."""


@merge_group.command("status")
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def status_command(obj: dict[str, Any], *, as_json: bool) -> None:
    """The merge, rebase or cherry-pick in progress, its unmerged files and changed files still holding markers."""
    result = merge_tools(obj, engine=False)["merge_conflicts"]()
    if as_json:
        click.echo(compact_json(result))
        return
    click.echo(status_lines(result))


def status_lines(result: Mapping[str, Any]) -> str:
    lines = [f"{result.get('operation') or 'no operation'} in progress" + _incoming(result)]
    for entry in result.get("unmerged", []):
        detail = f"{entry['hunks']} hunk(s)" if "hunks" in entry else entry.get("note", "")
        if entry.get("suggested"):
            detail += f", {entry['suggested']} with a suggestion"
        if entry.get("leftoverMarkers"):
            detail += f", stray markers at lines {', '.join(map(str, entry['leftoverMarkers']))}"
        lines.append(f"  unmerged {entry['path']}: {detail}")
    for entry in result.get("markersInChangedFiles", []):
        lines.append(f"  markers left in {entry['path']} at lines {', '.join(map(str, entry['markerLines']))}")
    lines.extend(f"  {result[key]}" for key in ("warning", "note") if result.get(key))
    return "\n".join(lines)


def _incoming(result: Mapping[str, Any]) -> str:
    return f": {result['incoming']}" if result.get("incoming") else ""


@merge_group.command("show")
@click.argument("path", type=click.Path(path_type=Path))
@click.option("--hunk", type=int, default=None, help="Only this hunk, with more lines per side.")
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def show_command(obj: dict[str, Any], path: Path, *, hunk: int | None, as_json: bool) -> None:
    """The conflict hunks of PATH as numbered lines, each with a suggestion where one side left it unchanged."""
    relative = common.shown_path((obj["root"] / path).resolve(), obj["root"].resolve())
    result = merge_tools(obj, engine=False)["merge_conflicts"](relative, hunk=hunk)
    if as_json:
        click.echo(compact_json(result))
        return
    fail_on_error(result)
    click.echo("\n".join(show_lines(result)))


def show_lines(result: Mapping[str, Any]) -> list[str]:
    lines = [f"{result['path']}: {result['hunks']} hunk(s)"]
    for entry in result.get("detail", []):
        head = f"hunk {entry['hunk']} (lines {entry['lines']}): ours {entry['ours']}, theirs {entry['theirs']}"
        if entry.get("suggestion"):
            head += f"; suggestion: {entry['suggestion']['take']} ({entry['suggestion']['why']})"
        lines.append(head)
        if entry.get("keepingBoth"):
            lines.append(f"  keeping both {entry['keepingBoth']}")
        for title, key in (("before", "before"), ("ours", "oursLines"), ("base", "baseLines")):
            lines.extend(_section(title, entry.get(key)))
        for title, key in (("theirs", "theirsLines"), ("after", "after")):
            lines.extend(_section(title, entry.get(key)))
    for item in result.get("leftoverMarkers", []):
        lines.append(f"stray marker at line {item['line']}: {item['marker']}")
    lines.extend(str(result[key]) for key in ("leftoverAdvice", "tip", "note") if result.get(key))
    return lines


def _section(title: str, rows: Sequence[str] | None) -> list[str]:
    if rows is None:
        return []
    return [f"  {title}:", *(f"    {row}" for row in rows)]


@merge_group.command("resolve")
@click.argument("path", type=click.Path(path_type=Path))
@click.argument("takes", nargs=-1)
@click.option("--suggested", is_flag=True, help="Take every suggestion for the hunks not named.")
@click.option("--apply", "apply_changes", is_flag=True, help="Write the file; otherwise a dry run.")
@click.option("--force", is_flag=True, help="Write even when the result does not parse.")
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def resolve_command(
    obj: dict[str, Any],
    path: Path,
    takes: tuple[str, ...],
    *,
    suggested: bool,
    apply_changes: bool,
    force: bool,
    as_json: bool,
) -> None:
    """Resolve hunks of PATH in one write: TAKES as N=ours, N=theirs, N=both, N=theirs-then-ours, N=base or
    N=neither. A Groovy result is parsed first and written only when it parses (--force overrides)."""
    resolutions = []
    for take in takes:
        number, separator, choice = take.partition("=")
        if not separator or not number.isdigit():
            raise click.BadParameter(f"{take!r}: write HUNK=CHOICE, for example 2=both", param_hint="TAKES")
        resolutions.append({"hunk": int(number), "take": choice})
    relative = common.shown_path((obj["root"] / path).resolve(), obj["root"].resolve())
    tool = merge_tools(obj, engine=True)["resolve_conflicts"]
    result = tool(relative, resolutions, suggested=suggested, apply=apply_changes, force=force)
    if as_json:
        click.echo(compact_json(result))
    else:
        fail_on_error(result)
        click.echo("\n".join(resolve_lines(result)))
    if result.get("error") or result.get("parses") is False:
        sys.exit(1)


def resolve_lines(result: Mapping[str, Any]) -> list[str]:
    lines = []
    for row in result.get("resolved", []):
        lines.append(f"hunk {row['hunk']}: {row['take']}, lines {row['lines']}" + _note(row))
        lines.extend(f"    {line}" for line in row.get("join", []))
    parses = result.get("parses")
    lines.append(f"parses: {'yes' if parses is True else 'no' if parses is False else parses}" + _note(result))
    lines.extend(f"  {error}" for error in result.get("errors", []))
    if result.get("remaining"):
        lines.append(f"unresolved hunks: {', '.join(map(str, result['remaining']))}")
    lines.append(f"written: {'yes' if result.get('written') else 'no'}")
    lines.extend(str(result[key]) for key in ("why", "next") if result.get(key))
    return lines


def _note(row: Mapping[str, Any]) -> str:
    return f" ({row['note']})" if row.get("note") else ""


@merge_group.command("review")
@click.argument("paths", nargs=-1, type=click.Path(path_type=Path))
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def review_command(obj: dict[str, Any], paths: tuple[Path, ...], *, as_json: bool) -> None:
    """Declarations a merge lost, deletions it undid and changes it dropped, in the Groovy files both sides changed
    (or PATHS); exits 1 when a file is flagged."""
    relative = [common.shown_path((obj["root"] / p).resolve(), obj["root"].resolve()) for p in paths]
    result = merge_tools(obj, engine=True)["merge_review"](relative or None)
    if as_json:
        click.echo(compact_json(result))
    else:
        fail_on_error(result)
        click.echo("\n".join(review_lines(result)))
    if result.get("error") or result.get("flagged"):
        sys.exit(1)


def review_lines(result: Mapping[str, Any]) -> list[str]:
    lines = [f"{result['operation']}: {result['files']} file(s) reviewed, {result['clean']} clean"]
    if not result.get("base"):
        lines.append("no merge base: deletions cannot be told from losses")
    for entry in result.get("flagged", []):
        lines.append(f"{entry['path']}: {entry.get('error') or ''}".rstrip(": "))
        lines.extend(f"  {error}" for error in entry.get("errors", []))
        for finding in entry.get("findings", []):
            lines.append(f"  {finding['line']}: {finding['symbol']} {finding['finding']}: {finding['detail']}")
    lines.append(str(result.get("next", "")))
    return lines
