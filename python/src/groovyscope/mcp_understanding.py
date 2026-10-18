"""MCP tools for understanding code and judging names: explain_code (what a class or method does, as facts),
relationship_graph (classes and how they relate, drawable), check_naming (naming findings plus the facts an agent
needs to judge whether a name says what the thing does)."""

from __future__ import annotations

import logging
from collections.abc import Callable, Mapping
from pathlib import Path
from typing import Any

from groovyscope import diagram, interaction
from groovyscope import graph as graphs
from groovyscope.kiro import write_atomically
from groovyscope.mcp_server_support import Call, relative_path, to_uri

NAMING_CODES = tuple(f"GS{n:03d}" for n in range(20, 28))
GRAPH_KINDS = ("extends", "implements", "has", "uses", "calls")
MAX_MERMAID_CHARS = 12000
MAX_FACT_LISTS = 8

LOG = logging.getLogger("groovyscope.mcp")


def compact_method(method: Mapping[str, Any]) -> dict[str, Any]:
    """A method's facts with the long lists cut to what an agent reads first."""
    entry: dict[str, Any] = {
        "name": method.get("name"),
        "visibility": method.get("visibility"),
        "returns": method.get("returns"),
        "parameters": [f"{p.get('type')} {p.get('name')}" for p in method.get("parameters", [])],
        "lines": [int(method.get("startLine", 0)) + 1, int(method.get("endLine", 0)) + 1],
        "branches": method.get("branches"),
        "callerCount": method.get("callerCount"),
    }
    for key in ("static", "abstract", "constructor", "dynamicReturn"):
        if method.get(key):
            entry[key] = True
    for key in ("calls", "callers", "reads", "writes", "throws", "catches", "externals"):
        values = method.get(key) or []
        if values:
            entry[key] = list(values)[:MAX_FACT_LISTS]
            if len(values) > MAX_FACT_LISTS:
                entry[key + "More"] = len(values) - MAX_FACT_LISTS
    if "calls" in entry:
        entry["calls"] = [
            {"target": c.get("target"), "count": c.get("count"), "confidence": c.get("confidence")}
            if isinstance(c, Mapping)
            else c
            for c in entry["calls"]
        ]
    return entry


def compact_class(item: Mapping[str, Any], root: Path) -> dict[str, Any]:
    entry: dict[str, Any] = {
        "kind": item.get("kind"),
        "name": item.get("name"),
        "path": relative_path(str(item.get("uri") or ""), root) if item.get("uri") else None,
        "lines": [int(item.get("startLine", 0)) + 1, int(item.get("endLine", 0)) + 1],
        "modifiers": item.get("modifiers") or [],
        "annotations": item.get("annotations") or [],
        "extends": item.get("superClass"),
        "implements": item.get("interfaces") or [],
        "fields": [
            {
                "name": f.get("name"),
                "type": f.get("type"),
                "visibility": f.get("visibility"),
                **({"final": True} if f.get("final") else {}),
                **({"static": True} if f.get("static") else {}),
            }
            for f in item.get("fields", [])
        ],
        "methods": [compact_method(m) for m in item.get("methods", [])],
        "responsibilities": [
            {"verb": r.get("verb"), "methods": r.get("methods")} for r in item.get("responsibilities", [])
        ],
        "usedBy": item.get("usedBy") or [],
        "uses": item.get("uses") or [],
        "callerCount": item.get("callerCount"),
    }
    externals = sorted({e for m in item.get("methods", []) for e in (m.get("externals") or [])})
    if externals:
        entry["externals"] = externals
    return entry


def compact_explanation(item: Mapping[str, Any], root: Path) -> dict[str, Any]:
    kind = str(item.get("kind") or "class")
    if kind == "method":
        entry = compact_method(item)
        entry["kind"] = "method"
        entry["class"] = item.get("class")
        if item.get("uri"):
            entry["path"] = relative_path(str(item["uri"]), root)
        return entry
    if kind in {"field", "property"}:
        return {
            "kind": kind,
            "name": item.get("name"),
            "type": item.get("type"),
            "class": item.get("class"),
            "visibility": item.get("visibility"),
            "final": item.get("final"),
            "static": item.get("static"),
        }
    return compact_class(item, root)


def naming_facts(item: Mapping[str, Any], root: Path) -> list[dict[str, Any]]:
    """Per declaration of a class: what an agent needs to judge the name against what the thing is and does."""
    facts: list[dict[str, Any]] = []
    owner = str(item.get("simpleName") or item.get("name"))
    path = relative_path(str(item.get("uri") or ""), root) if item.get("uri") else None
    facts.append(
        {
            "name": owner,
            "kind": item.get("kind"),
            "path": path,
            "line": int(item.get("startLine", 0)) + 1,
            "responsibilities": [r.get("verb") for r in item.get("responsibilities", [])],
            "usedBy": len(item.get("usedBy") or []),
        }
    )
    for field in item.get("fields", []):
        facts.append(
            {
                "name": field.get("name"),
                "kind": "property" if field.get("property") else "field",
                "class": owner,
                "type": field.get("type"),
                "line": int(field.get("line", 0)) + 1,
            }
        )
    for method in item.get("methods", []):
        if method.get("constructor"):
            continue
        entry: dict[str, Any] = {
            "name": method.get("name"),
            "kind": "method",
            "class": owner,
            "returns": method.get("returns"),
            "parameters": [f"{p.get('type')} {p.get('name')}" for p in method.get("parameters", [])],
            "line": int(method.get("startLine", 0)) + 1,
            "callerCount": method.get("callerCount"),
        }
        for key in ("writes", "externals", "throws"):
            if method.get(key):
                entry[key] = list(method[key])[:MAX_FACT_LISTS]
        facts.append(entry)
    return facts


def drawio_target(root: Path, path: str) -> Path:
    """Where a draw.io file may be written: inside the workspace, with the .drawio extension."""
    target = (root / path).resolve()
    if not target.is_relative_to(root.resolve()):
        msg = f"{path} is outside the workspace"
        raise ValueError(msg)
    if target.suffix.lower() != ".drawio":
        msg = f"{path} must end in .drawio"
        raise ValueError(msg)
    if target.exists():
        try:
            head = target.read_text(encoding="utf-8", errors="replace")[:200]
        except OSError as exc:
            msg = f"{path} cannot be read: {exc}"
            raise ValueError(msg) from exc
        if not target.is_file() or "<mxfile" not in head:
            msg = f"{path} exists and is not a draw.io file; choose another name"
            raise ValueError(msg)
    return target


def understanding_tools(
    root: Path,
    call: Call,
    cap: int,
    others: Mapping[str, Callable[..., Any]],
    *,
    confirm_policy: str = interaction.POLICY_AUTO,
    shared_callers: int = 5,
) -> dict[str, Callable[..., Any]]:
    """The tools, bound to one workspace and engine session; ``others`` supplies ``check``. ``shared_callers`` is
    ``diagram.sharedHelperCallers``."""

    def explain_code(symbol: str | None = None, path: str | None = None) -> dict[str, Any]:
        """What a class, method, field or whole file does, as facts from its tree and the call graph: members with
        types and visibility, each method's parameters, return, branches, the calls it makes (with confidence) and
        receives, the fields it reads and writes, the exceptions it throws and catches, the external world it touches
        (http, file, sql, process, environment, clock, random, logging), public methods grouped by leading verb,
        and the classes it is used by and uses. SYMBOL: `Class`, `pkg.Class`, `Class.method` or `Class#method`;
        PATH: every class of a file. Turn the facts into prose; do not guess from names."""
        params: dict[str, Any] = {"root": str(root)}
        if symbol:
            params["symbol"] = symbol
        elif path:
            params["uri"] = to_uri(root, path)
        else:
            return {"error": "give a symbol (Class, Class.method) or a path"}
        raw = call("groovyscope/explainCode", params)
        items = [compact_explanation(item, root) for item in (raw or {}).get("items", [])]
        return {"found": bool(items), "items": items[:cap]}

    def check_naming(paths: list[str] | None = None, limit: int | None = None, cursor: int = 0) -> dict[str, Any]:
        """Naming findings (GS020 to GS027: booleans not phrased as questions, getters that change state, verbs and
        nouns swapped, names that say nothing, abbreviations, type prefixes, names contradicting the type, mixed
        vocabulary) for PATHS or the workspace, plus `declarations`: every class, field and method with its type,
        what it writes and touches and how often it is called, so you can judge whether each name says what the
        thing is and does. Propose renames and apply them only through rename (dry run first)."""
        findings = others["check"](
            paths=paths, codes=list(NAMING_CODES), include=["practices"], limit=limit, cursor=cursor
        )
        declarations: list[dict[str, Any]] = []
        for path in (paths or [])[:cap]:
            try:
                raw = call("groovyscope/explainCode", {"root": str(root), "uri": to_uri(root, path)})
            except ValueError:
                continue
            for item in (raw or {}).get("items", []):
                declarations.extend(naming_facts(item, root))
        result = dict(findings)
        result["declarations"] = declarations[: cap * 4]
        result["declarationCount"] = len(declarations)
        if not paths:
            result["note"] = "pass paths to get the declaration facts for the files you are judging"
        return result

    return {
        "explain_code": explain_code,
        "check_naming": check_naming,
        **graph_tools(root, call, cap, confirm_policy=confirm_policy, shared_callers=shared_callers),
    }


def drawing(raw: Mapping[str, Any], call: Call, shared_callers: int) -> tuple[graphs.Layout, str]:
    """The layout from the engine's layered layout (`groovyscope/layout`), or groovyscope's own when the engine
    cannot give one (an engine older than the package): the drawing and which layout made it."""
    try:
        return diagram.layout(
            raw, lambda body: call("groovyscope/layout", dict(body)), shared_callers=shared_callers
        ), "layered"
    except (RuntimeError, ValueError, OSError, KeyError, TypeError) as exc:
        LOG.info("engine layout unavailable (%s); using the basic layout", exc)
        return graphs.layout(raw), "basic"


def write_diagram(
    root: Path,
    drawio_path: str,
    raw: Mapping[str, Any],
    result: dict[str, Any],
    *,
    confirm_policy: str,
    cap: int,
    call: Call,
    shared_callers: int,
) -> None:
    """Writes the draw.io file after the user's yes (when the client can ask), and says in ``result`` where it went
    (relative and absolute), what was asked, and any layout problem left."""
    try:
        target = drawio_target(root, drawio_path)
    except ValueError as exc:
        result["error"] = str(exc)
        return
    shown = relative_path(target.as_uri(), root)
    decision = interaction.confirm(f"Write the class diagram to {shown}?", policy=confirm_policy)
    if decision == interaction.NOT_OFFERED:
        result["confirmationNote"] = interaction.NOT_OFFERED_NOTE
    if decision != interaction.UNSUPPORTED:
        result["confirmation"] = decision
    if decision in {interaction.DECLINED, interaction.CANCELLED}:
        result["written"] = None
        result["note"] = "the user said no; no file was written"
        return
    drawn, engine_used = drawing(raw, call, shared_callers)
    target.parent.mkdir(parents=True, exist_ok=True)
    write_atomically(target, graphs.to_drawio(raw, drawn, title=target.stem))
    result["written"] = shown
    result["writtenTo"] = str(target)  # the absolute path: the workspace root may not be where the user looks
    result["page"] = {"paper": drawn["paper"], "orientation": drawn["orientation"], "font": drawn["font"]}
    result["lines"] = {"layout": engine_used, **diagram.route_quality(drawn, raw)}
    shared = sum(1 for box in drawn["nodes"] if " · used by " in box["sublabel"])
    if shared:
        result["lines"]["sharedHelpers"] = shared
    problems = graphs.check_layout(drawn)
    if problems:
        result["layoutProblems"] = problems[:cap]
    if drawn["note"]:
        result["layoutNote"] = drawn["note"]


def graph_tools(
    root: Path, call: Call, cap: int, *, confirm_policy: str = interaction.POLICY_AUTO, shared_callers: int = 5
) -> dict[str, Callable[..., Any]]:
    """relationship_graph, bound to one workspace and engine session."""

    def relationship_graph(
        symbols: list[str] | None = None,
        paths: list[str] | None = None,
        *,
        kinds: list[str] | None = None,
        depth: int = 1,
        level: str = "class",
        max_nodes: int = 60,
        drawio_path: str | None = None,
        mermaid: bool = False,
    ) -> dict[str, Any]:
        """How the workspace's classes relate: extends, implements, has (a field typed with another class), uses (a
        public method takes or returns another class) and calls (aggregated call graph edges with the weakest
        confidence). SYMBOLS or PATHS seed the graph and DEPTH is how many hops to show around them (none given:
        the whole workspace, capped at MAX_NODES, best connected first); LEVEL `method` shows one class's methods.
        DRAWIO_PATH writes a draw.io file on A4, A3 or A2, whichever keeps the text readable (open it in
        diagrams.net); MERMAID adds a class diagram for the answer. The graph itself is returned compact: nodes
        and edges, no file bodies."""
        for kind in kinds or []:
            if kind not in GRAPH_KINDS:
                return {"error": f"unknown edge kind {kind!r}", "kinds": list(GRAPH_KINDS)}
        params: dict[str, Any] = {
            "root": str(root),
            "symbols": list(symbols or []),
            "uris": [to_uri(root, p) for p in paths or []],
            "kinds": list(kinds or []),
            "depth": max(0, int(depth)),
            "maxNodes": max(1, int(max_nodes)),
            "level": level,
        }
        raw = call("groovyscope/graph", params) or {}
        for node in raw.get("nodes", []):
            if node.get("uri"):
                node["path"] = relative_path(str(node["uri"]), root)
        result = graphs.compact(raw, cap)
        if raw.get("note"):
            result["note"] = raw["note"]
        if drawio_path:
            write_diagram(
                root,
                drawio_path,
                raw,
                result,
                confirm_policy=confirm_policy,
                cap=cap,
                call=call,
                shared_callers=shared_callers,
            )
        if mermaid:
            text = graphs.to_mermaid(raw)
            result["mermaid"] = text if len(text) <= MAX_MERMAID_CHARS else text[:MAX_MERMAID_CHARS] + "\n%% cut\n"
        return result

    return {"relationship_graph": relationship_graph}
