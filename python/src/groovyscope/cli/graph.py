"""Code understanding: the relationship graph, explain-code, impact and the call graph."""

from __future__ import annotations

import sys
from collections.abc import Mapping
from pathlib import Path
from typing import Any

import click

from groovyscope.cli import common
from groovyscope.kiro import (
    write_atomically,
)
from groovyscope.render import compact_json


@common.main.command("explain-code")
@click.argument("target")
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def explain_code_command(obj: dict[str, Any], target: str, as_json: bool) -> None:
    """What TARGET does, as facts: TARGET is a class or member (pkg.Class, Class.method, Class#method) or a file."""
    socket_file, effective = common.daemon_socket_for(obj)
    tools = common.workspace_tools(obj, socket_file, effective)
    is_file = (obj["root"] / target).is_file() or target.endswith((".groovy", ".gradle", ".java"))
    result = tools["explain_code"](path=target) if is_file else tools["explain_code"](symbol=target)
    if as_json:
        click.echo(compact_json(result))
    else:
        for line in render_explanation(result):
            click.echo(line)
    if not result.get("found"):
        common.LOG.error(f"no declaration found for {target}")
        sys.exit(2)


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


@common.main.command("graph")
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
    socket_file, effective = common.daemon_socket_for(obj)
    tools = common.workspace_tools(obj, socket_file, effective)
    wanted = [k.strip() for k in kinds.split(",") if k.strip()]
    relative = [common.shown_path(obj["root"] / p, obj["root"]) for p in paths]
    if output_format == "drawio":
        target = out or Path("groovyscope-graph.drawio")
        result = tools["relationship_graph"](
            symbols=list(symbols),
            paths=relative,
            kinds=wanted,
            depth=depth,
            level=level,
            max_nodes=max_nodes,
            drawio_path=common.shown_path(obj["root"] / target, obj["root"]),
        )
        if result.get("error"):
            common.LOG.error(f"groovyscope: {result['error']}")
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
            common.LOG.warning(f"layout: {problem}")
        if result.get("layoutNote"):
            common.LOG.warning(f"note: {result['layoutNote']}")
        if result.get("truncated"):
            common.LOG.warning("note: the graph was capped; raise --max-nodes or narrow the seeds")
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
        common.LOG.error(f"groovyscope: {result['error']}")
        sys.exit(1)
    text = result["mermaid"] if output_format == "mermaid" else compact_json(result)
    if out is not None:
        write_atomically(out if out.is_absolute() else obj["root"] / out, text)
        click.echo(f"written to {common.shown_path(out if out.is_absolute() else obj['root'] / out, obj['root'])}")
    else:
        click.echo(text, nl=output_format != "mermaid")


def describe_symbol(symbol: dict[str, Any], root: Path) -> str:
    location = symbol.get("location")
    where = ""
    if location:
        path = common.uri_to_path(location["uri"])
        try:
            shown = path.relative_to(root).as_posix()
        except ValueError:
            shown = path.as_posix()
        where = f" ({shown}:{int(location['line']) + 1}:{int(location['character']) + 1})"
    detail = symbol.get("detail") or symbol.get("name")
    container = symbol.get("container")
    return f"{container + '.' if container else ''}{detail}{where}"


def describe_site(site: dict[str, Any], root: Path) -> str:
    where = common.uri_to_path(site["site"]["uri"])
    try:
        shown = where.relative_to(root).as_posix()
    except ValueError:
        shown = where.as_posix()
    position = f"{shown}:{int(site['site']['line']) + 1}:{int(site['site']['character']) + 1}"
    return f"{position} in {describe_symbol(site['caller'], root)} [{site['confidence']}]"


@common.main.command()
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
    socket_file, _ = common.daemon_socket_for(obj)
    params = {"root": str(obj["root"]), "change": change, **common.target_params(obj, target)}
    result = _RPC_LIST(socket_file, "groovyscope/impact", params)
    if as_json:
        click.echo(compact_json(result))
        return
    if not result:
        common.LOG.error(f"no declaration found for {target}")
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


@common.main.command()
@click.argument("target")
@click.option("--direction", type=click.Choice(["in", "out"]), default="in", show_default=True)
@click.option("--depth", type=click.IntRange(1, 6), default=2, show_default=True)
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def callgraph(obj: dict[str, Any], target: str, direction: str, depth: int, as_json: bool) -> None:
    """Callers of (in) or calls made by (out) TARGET, to the given depth."""
    socket_file, _ = common.daemon_socket_for(obj)
    params = {"root": str(obj["root"]), "direction": direction, "depth": depth, **common.target_params(obj, target)}
    result = common.engine_call(socket_file, "groovyscope/callGraph", params)
    if as_json:
        click.echo(compact_json(result))
        return
    root = obj["root"]
    if not result.get("roots"):
        common.LOG.error(f"no declaration found for {target}")
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
        result = common._RPC(socket_file, method, params, common.RPC_TIMEOUT_SECONDS)
    except (OSError, RuntimeError, ValueError) as exc:
        common.LOG.error(f"groovyscope: {method} failed: {exc}")
        sys.exit(1)
    return [dict(item) for item in result] if isinstance(result, list) else []


_RPC_LIST = _rpc_list
