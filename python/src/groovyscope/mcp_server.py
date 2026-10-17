"""The MCP server: higher-order tools over one persistent JSON-RPC session with the engine daemon.

Every tool returns compact JSON (relative paths, line:col, kinds, pagination) and never a file body.
"""

from __future__ import annotations

import urllib.parse
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any

from mcp.server.mcpserver import MCPServer

from groovyscope import daemon, rpc
from groovyscope.edits import apply_edits, changed_line_count
from groovyscope.render import page

SERVER_NAME = "groovyscope"
INSTRUCTIONS = (
    "Groovy code intelligence for this workspace. Use these tools instead of grep, sed, head or tail to "
    "locate Groovy symbols. Every answer carries a confidence; DYNAMIC_UNKNOWN means matched by name only. "
    "Paths are workspace-relative; lines and columns are 1-based."
)
Call = Callable[[str, dict[str, Any]], Any]
SEVERITY_NAMES = {1: "error", 2: "warning", 3: "info", 4: "hint"}
SEVERITY_ORDER = {"error": 0, "warning": 1, "info": 2, "hint": 3}
KIND_FILTERS = {
    "class": {"class", "interface", "trait", "enum", "record", "annotation"},
    "method": {"method", "constructor"},
    "field": {"field", "property"},
    "variable": {"variable", "parameter"},
}
LSP_SYMBOL_KINDS = {
    1: "file",
    2: "module",
    3: "namespace",
    4: "package",
    5: "class",
    6: "method",
    7: "property",
    8: "field",
    9: "constructor",
    10: "enum",
    11: "interface",
    12: "function",
    13: "variable",
    14: "constant",
    15: "string",
    16: "number",
    17: "boolean",
    18: "array",
    19: "object",
    20: "key",
    21: "null",
    22: "enumMember",
    23: "struct",
    24: "event",
    25: "operator",
    26: "typeParameter",
}


def relative_path(uri: str, root: Path) -> str:
    path = Path(urllib.parse.unquote(urllib.parse.urlparse(uri).path))
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.as_posix()


def to_uri(root: Path, path: str) -> str:
    return (root / path).resolve().as_uri()


def compact_location(location: Mapping[str, Any] | None, root: Path) -> dict[str, Any] | None:
    """``{path, line, col}`` with 1-based line and column, from an engine or LSP location."""
    if not location:
        return None
    if "range" in location:
        start = location["range"]["start"]
        return {
            "path": relative_path(location["uri"], root),
            "line": int(start["line"]) + 1,
            "col": int(start["character"]) + 1,
        }
    return {
        "path": relative_path(location["uri"], root),
        "line": int(location.get("line", 0)) + 1,
        "col": int(location.get("character", 0)) + 1,
    }


def compact_symbol(symbol: Mapping[str, Any], root: Path) -> dict[str, Any]:
    kind = symbol.get("kind")
    if isinstance(kind, int):
        kind = LSP_SYMBOL_KINDS.get(kind, str(kind))
    return {
        "name": symbol.get("name"),
        "kind": kind,
        "container": symbol.get("container") or symbol.get("containerName"),
        "detail": symbol.get("detail"),
        "location": compact_location(symbol.get("location"), root),
    }


def compact_site(site: Mapping[str, Any], root: Path) -> dict[str, Any]:
    return {
        "caller": compact_symbol(site["caller"], root),
        "callee": compact_symbol(site["callee"], root),
        "site": compact_location(site["site"], root),
        "confidence": site.get("confidence"),
    }


def compact_diagnostic(uri: str, item: Mapping[str, Any], root: Path) -> dict[str, Any]:
    start = item.get("range", {}).get("start", {})
    return {
        "path": relative_path(uri, root),
        "line": int(start.get("line", 0)) + 1,
        "col": int(start.get("character", 0)) + 1,
        "severity": SEVERITY_NAMES.get(int(item.get("severity", 3)), "info"),
        "source": item.get("source"),
        "code": item.get("code"),
        "message": item.get("message"),
    }


def compact_report(report: Mapping[str, Any], root: Path, cap: int) -> dict[str, Any]:
    return {
        "symbol": compact_symbol(report["symbol"], root),
        "change": report.get("change"),
        "riskScore": report.get("riskScore"),
        "riskLevel": report.get("riskLevel"),
        "counts": report.get("counts", {}),
        "directCallers": [compact_site(s, root) for s in report.get("directCallers", [])][:cap],
        "transitiveCallers": [compact_site(s, root) for s in report.get("transitiveCallers", [])][:cap],
        "overrides": [compact_symbol(s, root) for s in report.get("overrides", [])],
        "tests": [compact_site(s, root) for s in report.get("tests", [])][:cap],
        "dynamicUnknowns": [compact_site(s, root) for s in report.get("dynamicUnknowns", [])][:cap],
        "suggestedChecks": report.get("suggestedChecks", []),
    }


Paged = Callable[[Sequence[Any], int | None, int], dict[str, Any]]


def pager(output: Mapping[str, Any]) -> tuple[Paged, int]:
    """A paging function honouring output.maxResults / output.maxBytes, plus the result cap it applies."""
    max_results = int(output.get("maxResults", 50))
    max_bytes = int(output.get("maxBytes", 16000))

    def paged(items: Sequence[Any], limit: int | None, cursor: int) -> dict[str, Any]:
        size = min(max_results, limit) if limit else max_results
        return page(list(items), max_results=size, max_bytes=max_bytes, cursor=cursor)

    return paged, max_results


def navigation_tools(root: Path, call: Call, paged: Paged, cap: int) -> dict[str, Callable[..., Any]]:
    """find_symbol, goto_definition, find_references, call_graph and impact_of."""

    def find_symbol(query: str, kind: str | None = None, limit: int | None = None, cursor: int = 0) -> dict[str, Any]:
        """Find declarations whose name contains QUERY (case-insensitive). KIND: class, method, field or variable."""
        raw = call("groovyscope/symbols", {"root": str(root), "query": query})
        symbols = [compact_symbol(item, root) for item in raw.get("symbols", [])]
        if kind:
            allowed = KIND_FILTERS.get(kind.lower(), {kind.lower()})
            symbols = [s for s in symbols if (s.get("kind") or "").lower() in allowed]
        return paged(symbols, limit, cursor)

    def goto_definition(path: str, line: int, col: int) -> dict[str, Any]:
        """The declaration under PATH:LINE:COL (1-based) with its confidence; location is null outside the workspace."""
        params = {"root": str(root), "uri": to_uri(root, path), "line": line - 1, "character": col - 1}
        raw = call("groovyscope/definition", params)
        if not raw:
            return {"found": False}
        return {"found": True, "symbol": compact_symbol(raw["symbol"], root), "confidence": raw.get("confidence")}

    def find_references(
        path: str,
        line: int,
        col: int,
        *,
        limit: int | None = None,
        cursor: int = 0,
        include_declaration: bool = False,
    ) -> dict[str, Any]:
        """Every place that refers to the symbol under PATH:LINE:COL (1-based), each with a confidence."""
        params = {
            "root": str(root),
            "uri": to_uri(root, path),
            "line": line - 1,
            "character": col - 1,
            "includeDeclaration": include_declaration,
        }
        raw = call("groovyscope/references", params)
        refs = [
            {**(compact_location(item, root) or {}), "confidence": item.get("confidence")}
            for item in raw.get("references", [])
        ]
        result = paged(refs, limit, cursor)
        result["counts"] = raw.get("counts", {})
        return result

    def call_graph(symbol: str, direction: str = "in", depth: int = 2) -> dict[str, Any]:
        """Callers of (in) or calls made by (out) SYMBOL (pkg.Class.method, Class#method or Class), to DEPTH levels."""
        raw = call(
            "groovyscope/callGraph", {"root": str(root), "symbol": symbol, "direction": direction, "depth": depth}
        )
        result = paged([compact_site(edge, root) for edge in raw.get("edges", [])], None, 0)
        result["direction"] = raw.get("direction")
        result["depth"] = raw.get("depth")
        result["nodes"] = [compact_symbol(node, root) for node in raw.get("nodes", [])][:cap]
        return result

    def impact_of(symbol: str, change: str = "signature") -> dict[str, Any]:
        """What breaks if SYMBOL changes (CHANGE: signature, rename, delete, behaviour): callers, tests, risk."""
        raw = call("groovyscope/impact", {"root": str(root), "symbol": symbol, "change": change})
        reports = [compact_report(report, root, cap) for report in raw or []]
        return {"found": bool(reports), "reports": reports}

    return {
        "find_symbol": find_symbol,
        "goto_definition": goto_definition,
        "find_references": find_references,
        "call_graph": call_graph,
        "impact_of": impact_of,
    }


def quality_tools(
    root: Path, call: Call, paged: Paged, cap: int, environ: Mapping[str, str]
) -> dict[str, Callable[..., Any]]:
    """check, format, find_dynamic_code and workspace_status."""

    def check(
        paths: list[str] | None = None,
        profile: str | None = None,
        include: list[str] | None = None,
        limit: int | None = None,
        cursor: int = 0,
    ) -> dict[str, Any]:
        """Lint (CodeNarc), compile and type-check findings for PATHS (whole workspace when empty), worst first."""
        params: dict[str, Any] = {
            "root": str(root),
            "uris": [to_uri(root, p) for p in paths or []],
            "include": include or [],
        }
        if profile:
            params["profile"] = profile
        raw = call("groovyscope/check", params)
        findings = [
            compact_diagnostic(uri, item, root) for uri, items in raw.get("diagnostics", {}).items() for item in items
        ]
        findings.sort(key=lambda f: (SEVERITY_ORDER.get(f["severity"], 9), f["path"], f["line"]))
        result = paged(findings, limit, cursor)
        result["summary"] = raw.get("summary", {})
        return result

    def format_files(paths: list[str] | None = None, apply: bool = False) -> dict[str, Any]:
        """Formatting edits for PATHS (whole workspace when empty). APPLY writes the files; otherwise only counts."""
        raw = call("groovyscope/format", {"root": str(root), "uris": [to_uri(root, p) for p in paths or []]})
        files = []
        for uri, edits in raw.get("edits", {}).items():
            target = Path(urllib.parse.unquote(urllib.parse.urlparse(uri).path))
            before = target.read_text(encoding="utf-8")
            after = apply_edits(before, edits)
            if apply:
                target.write_text(after, encoding="utf-8")
            files.append(
                {"path": relative_path(uri, root), "linesChanged": changed_line_count(before, after), "applied": apply}
            )
        return {"checked": raw.get("checked", 0), "changed": len(files), "files": files[:cap]}

    def find_dynamic_code(paths: list[str] | None = None, limit: int | None = None, cursor: int = 0) -> dict[str, Any]:
        """Constructs that defeat static analysis: untyped classes, def on public API, methodMissing, metaClass."""
        raw = call("groovyscope/dynamicCode", {"root": str(root), "uris": [to_uri(root, p) for p in paths or []]})
        findings = [
            {
                "kind": item.get("kind"),
                "symbol": item.get("symbol"),
                "detail": item.get("detail"),
                "location": compact_location(item.get("location"), root),
            }
            for item in raw.get("findings", [])
        ]
        result = paged(findings, limit, cursor)
        result["counts"] = raw.get("counts", {})
        return result

    def workspace_status() -> dict[str, Any]:
        """Daemon, engine version, root, file count, trust flag and index state."""
        status = call("groovyscope/status", {"root": str(root)})
        info = daemon.info(root, environ)
        return {
            "root": str(root),
            "daemon": {"socket": info["socket"], "running": info["running"], "log": info["log"]},
            "engine": status,
        }

    return {
        "check": check,
        "format": format_files,
        "find_dynamic_code": find_dynamic_code,
        "workspace_status": workspace_status,
    }


def build_tools(
    root: Path, call: Call, output: Mapping[str, Any], environ: Mapping[str, str]
) -> dict[str, Callable[..., Any]]:
    """The tool functions, keyed by their MCP names, bound to one workspace root and one engine session."""
    paged, cap = pager(output)
    return {**navigation_tools(root, call, paged, cap), **quality_tools(root, call, paged, cap, environ)}


def build_server(root: Path, call: Call, output: Mapping[str, Any], environ: Mapping[str, str]) -> MCPServer:
    """An MCP server exposing the tools of {@link build_tools}."""
    server = MCPServer(SERVER_NAME, instructions=INSTRUCTIONS)
    for name, function in build_tools(root, call, output, environ).items():
        server.tool(name=name, description=function.__doc__)(function)
    return server


def serve(root: Path, socket_file: Path, output: Mapping[str, Any], environ: Mapping[str, str]) -> int:
    """Open one engine session, run the MCP server on stdio until the client disconnects, close the session."""
    session = rpc.open_session(socket_file)
    try:
        rpc.call(session, "initialize", {"rootUri": root.as_uri(), "capabilities": {}, "processId": None})
        rpc.notify_session(session, "initialized", {})

        def call(method: str, params: dict[str, Any]) -> Any:
            return rpc.call(session, method, params, timeout=600.0)

        build_server(root, call, output, environ).run(transport="stdio")
    finally:
        rpc.close_session(session)
    return 0
