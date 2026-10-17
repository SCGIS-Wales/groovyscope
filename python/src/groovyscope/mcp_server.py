"""The MCP server: higher-order tools over one persistent JSON-RPC session with the engine daemon.

Every tool returns compact JSON (relative paths, line:col, kinds, pagination) and never a file body.
"""

from __future__ import annotations

import functools
import json
import logging
import threading
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any

from mcp.server.mcpserver import MCPServer
from mcp.types import CallToolResult, TextContent, ToolAnnotations

from groovyscope import daemon, diagnostics, mcp_search, practices, rpc
from groovyscope.edits import changed_line_count, file_edits, uri_path, write_document
from groovyscope.mcp_server_support import Call, Paged, clip, relative_path, to_uri
from groovyscope.render import page

SERVER_NAME = "groovyscope"
LOG = logging.getLogger("groovyscope.mcp")
INSTRUCTIONS = (
    "Groovy code intelligence for this workspace. Use these tools instead of grep, sed, head or tail to "
    "locate Groovy symbols, and document_symbols or hover instead of reading whole files. Every answer carries a "
    "confidence; DYNAMIC_UNKNOWN means matched by name only. Lists are paged: pass limit and the next_cursor you "
    "were given rather than asking for everything. Rename through rename (a dry run first), never by text replace. "
    "Paths are workspace-relative; lines and columns are 1-based. Before finishing a change, call review on the "
    "changed files and fix what it reports (fix applies the safe mechanical fixes); explain says why a code matters. "
    "When impact_of is asked about a change, report its transitive callers and tests too, not only direct callers. "
    "Files governed by a runtime profile (RP codes) run on the Groovy version and libraries the profile names: call "
    "profile_status before editing one, keep its descriptor parameters, test seams and protected methods intact, and "
    "use only constructs the profile marks as proven."
)
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


MESSAGE_CHARS = 400  # default for output.maxMessageChars


def compact_diagnostic(uri: str, item: Mapping[str, Any], root: Path, limit: int = MESSAGE_CHARS) -> dict[str, Any]:
    start = item.get("range", {}).get("start", {})
    data = item.get("data")
    fixable = {"fixable": True} if isinstance(data, Mapping) and data.get("fixable") else {}
    return {
        **fixable,
        "path": relative_path(uri, root),
        "line": int(start.get("line", 0)) + 1,
        "col": int(start.get("character", 0)) + 1,
        "severity": SEVERITY_NAMES.get(int(item.get("severity", 3)), "info"),
        "source": item.get("source"),
        "code": item.get("code"),
        "message": clip(item.get("message"), limit),
    }


PRIORITY_SEVERITIES = {1: {"error"}, 2: {"error", "warning"}, 3: {"error", "warning", "info"}}


def filter_findings(
    findings: list[dict[str, Any]], codes: Sequence[str] | None, max_priority: int | None
) -> list[dict[str, Any]]:
    """Only CODES (when given) and only findings at or above MAX_PRIORITY (1 errors, 2 + warnings, 3 + info)."""
    allowed = PRIORITY_SEVERITIES.get(max_priority or 0)
    wanted = set(codes or [])
    return [
        f
        for f in findings
        if (not wanted or f.get("code") in wanted) and (allowed is None or f.get("severity") in allowed)
    ]


def summarise(findings: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Findings without the per-file missing-dependency notes, plus counts per code and a one-line summary of
    the files that miss dependencies (fix: `groovyscope classpath`)."""
    missing = [f for f in findings if f.get("code") == "GS100"]
    rest = [f for f in findings if f.get("code") != "GS100"]
    counts: dict[str, int] = {}
    for finding in rest:
        counts[str(finding.get("code"))] = counts.get(str(finding.get("code")), 0) + 1
    extra: dict[str, Any] = {"byCode": dict(sorted(counts.items(), key=lambda kv: -kv[1]))}
    if missing:
        extra["missingDependencies"] = {
            "files": len(missing),
            "fix": "run `groovyscope classpath` (trusted workspace) so third-party classes resolve",
        }
    return (rest, extra)


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


def insight_tools(root: Path, call: Call, paged: Paged, cap: int) -> dict[str, Callable[..., Any]]:
    """hover, type_definition and document_symbols: the remaining LSP views, for agents."""

    def hover(path: str, line: int, col: int) -> dict[str, Any]:
        """What the editor shows on hover at PATH:LINE:COL (1-based): type, signature, documentation, runtime-profile
        parameter details and why any finding on that line matters."""
        raw = call("groovyscope/hover", position_params(root, path, line, col))
        if not raw:
            return {"found": False}
        return {"found": True, "markdown": clip(raw.get("markdown", ""), cap * 80)}

    def type_definition(path: str, line: int, col: int) -> dict[str, Any]:
        """The declaration of the type of the expression at PATH:LINE:COL (1-based)."""
        raw = call("groovyscope/typeDefinition", position_params(root, path, line, col))
        if not raw:
            return {"found": False}
        return {"found": True, "symbol": compact_symbol(raw["symbol"], root), "confidence": raw.get("confidence")}

    def document_symbols(
        path: str,
        *,
        depth: int = 2,
        kinds: list[str] | None = None,
        name: str | None = None,
        limit: int | None = None,
        cursor: int = 0,
    ) -> dict[str, Any]:
        """The outline of PATH: classes and their members to DEPTH levels, each with its first and last line, plus
        the file's line count. KINDS (class, method, field...) and NAME (substring) filter members; page with
        LIMIT and CURSOR. Use with read_symbol instead of reading the file."""
        raw = call("groovyscope/documentSymbols", {"root": str(root), "uri": to_uri(root, path)})
        wanted = {kind.lower() for kind in kinds or []}
        needle = (name or "").lower()

        def keep(item: Mapping[str, Any]) -> bool:
            kind = str(item.get("kind") or "").lower()
            return (not wanted or kind in wanted) and needle in str(item.get("name") or "").lower()

        def outline(item: Mapping[str, Any], level: int) -> dict[str, Any]:
            entry = compact_symbol(item, root)
            entry.pop("path", None)
            location = entry.pop("location", None) or {}
            entry["lines"] = [location.get("line"), int(item.get("endLine", location.get("line", 1) - 1)) + 1]
            children = item.get("children") or []
            if children and level < depth:
                entry["children"] = [outline(child, level + 1) for child in children if keep(child)]
            elif children:
                entry["members"] = len(children)
            return entry

        flat: list[dict[str, Any]] = []
        for item in raw.get("symbols", []):
            top = outline(item, 1)
            members = top.pop("children", None)
            flat.append(top)
            for member in members or []:
                flat.append({**member, "in": top.get("name")})
        result = paged(flat, limit, cursor)
        result["path"] = path
        result["fileLines"] = raw.get("lines")
        return result

    return {"hover": hover, "type_definition": type_definition, "document_symbols": document_symbols}


def position_params(root: Path, path: str, line: int, col: int) -> dict[str, Any]:
    return {"root": str(root), "uri": to_uri(root, path), "line": line - 1, "character": col - 1}


def quality_tools(
    root: Path, call: Call, paged: Paged, cap: int, environ: Mapping[str, str], *, clip_at: int = MESSAGE_CHARS
) -> dict[str, Callable[..., Any]]:
    """check, format, find_dynamic_code and workspace_status."""

    def check(
        *,
        paths: list[str] | None = None,
        profile: str | None = None,
        include: list[str] | None = None,
        codes: list[str] | None = None,
        max_priority: int | None = None,
        limit: int | None = None,
        cursor: int = 0,
    ) -> dict[str, Any]:
        """Lint, compile, type-check and practice (GS) findings for PATHS (whole workspace when empty), worst first,
        with counts per code. CODES keeps only those codes; MAX_PRIORITY 1 = errors, 2 = and warnings, 3 = and
        info. Findings marked fixable can be repaired by fix."""
        params: dict[str, Any] = {
            "root": str(root),
            "uris": [to_uri(root, p) for p in paths or []],
            "include": include or [],
        }
        if profile:
            params["profile"] = profile
        raw = call("groovyscope/check", params)
        findings = [
            compact_diagnostic(uri, item, root, clip_at)
            for uri, items in raw.get("diagnostics", {}).items()
            for item in items
        ]
        findings, extra = summarise(filter_findings(findings, codes, max_priority))
        findings.sort(key=lambda f: (SEVERITY_ORDER.get(f["severity"], 9), f["path"], f["line"]))
        result = paged(findings, limit, cursor)
        result["summary"] = raw.get("summary", {})
        result.update(extra)
        return result

    def format_files(paths: list[str] | None = None, apply: bool = False) -> dict[str, Any]:
        """Formatting edits for PATHS (whole workspace when empty). APPLY writes the files; otherwise only counts."""
        raw = call("groovyscope/format", {"root": str(root), "uris": [to_uri(root, p) for p in paths or []]})
        changed, refused = file_edits(raw.get("edits", {}), root)
        files = []
        for edit in changed:
            if apply:
                write_document(edit["path"], edit["after"])
            files.append(
                {
                    "path": relative_path(edit["path"].as_uri(), root),
                    "linesChanged": changed_line_count(edit["before"], edit["after"]),
                    "applied": apply,
                }
            )
        result: dict[str, Any] = {"checked": raw.get("checked", 0), "changed": len(files), "files": files[:cap]}
        if refused:
            result["refused"] = [relative_path(uri, root) for uri in refused]
        return result

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


def practice_tools(root: Path, call: Call, cap: int, clip_at: int = MESSAGE_CHARS) -> dict[str, Callable[..., Any]]:
    """explain, review, fix and profile_status."""

    def explain(code: str) -> dict[str, Any]:
        """What a diagnostic code means (GS001-GS012, a Groovy 6 checker, a CodeNarc rule), why it matters, the fix."""
        answer = call("groovyscope/explain", {"root": str(root), "code": code})
        return {"found": True, **answer} if answer else {"found": False, "code": code}

    def review(paths: list[str] | None = None, top: int = 5, max_priority: int | None = None) -> dict[str, Any]:
        """Compile, lint, type-check and practice findings for PATHS in one call, grouped by the review checklist,
        with counts per code; MAX_PRIORITY as in check. Missing dependencies are summarised, not listed."""
        raw = call(
            "groovyscope/check",
            {"root": str(root), "uris": [to_uri(root, p) for p in paths or []], "include": []},
        )
        findings = [
            compact_diagnostic(uri, item, root, clip_at)
            for uri, items in raw.get("diagnostics", {}).items()
            for item in items
        ]
        findings, extra = summarise(filter_findings(findings, None, max_priority))
        groups = practices.review(findings, max(1, min(top, cap)))
        result: dict[str, Any] = {"summary": raw.get("summary", {}), "groups": groups[:cap], **extra}
        governed = governed_files(paths or [])
        if governed:
            result["profiles"] = governed
        return result

    def governed_files(paths: Sequence[str]) -> dict[str, str]:
        """Profile name per governed path among PATHS (only explicit paths are looked up)."""
        found: dict[str, str] = {}
        for path in paths[:cap]:
            status = call("groovyscope/profile", {"root": str(root), "uri": to_uri(root, path)})
            if isinstance(status, Mapping) and status.get("governed"):
                found[path] = str(status.get("profile"))
        return found

    def profile_status(path: str) -> dict[str, Any]:
        """The runtime profile governing PATH: target Groovy, compiling service, the status of each construct on the
        runtime (blocked, unproven, proven...), descriptor parameters, test seams, protected methods, whether a
        contract baseline is recorded, and the profile's guidance."""
        status = call("groovyscope/profile", {"root": str(root), "uri": to_uri(root, path)})
        return {"path": path, **(dict(status) if isinstance(status, Mapping) else {"governed": False})}

    def fix(paths: list[str] | None = None, codes: list[str] | None = None, apply: bool = False) -> dict[str, Any]:
        """Safe fixes for PATHS (all when empty) and CODES (all fixable when empty). APPLY writes the files."""
        raw = call(
            "groovyscope/fix",
            {"root": str(root), "uris": [to_uri(root, p) for p in paths or []], "codes": codes or []},
        )
        changed, refused = file_edits(raw.get("edits", {}), root)
        files: list[dict[str, Any]] = []
        for edit in changed:
            if apply:
                write_document(edit["path"], edit["after"])
            uri = edit["path"].as_uri()
            files.append(
                {
                    "path": relative_path(uri, root),
                    "codes": next((v for k, v in raw.get("applied", {}).items() if uri_path(k) == edit["path"]), []),
                    "linesChanged": changed_line_count(edit["before"], edit["after"]),
                    "applied": apply,
                }
            )
        rejected = {relative_path(uri, root): why for uri, why in raw.get("rejected", {}).items()}
        rejected.update({relative_path(uri, root): "not a Groovy source inside the workspace" for uri in refused})
        return {"changed": len(files), "files": files[:cap], "rejected": rejected}

    def rename(path: str, line: int, col: int, new_name: str, apply: bool = False) -> dict[str, Any]:
        """Rename the symbol at PATH:LINE:COL (1-based) to NEW_NAME everywhere it is used. Refused when any use is
        matched by name only (dynamic Groovy). A dry run listing the edits per file unless APPLY."""
        raw = call("groovyscope/rename", {**position_params(root, path, line, col), "newName": new_name})
        if raw.get("refused"):
            return {"renamed": False, "refused": raw["refused"]}
        changed, refused = file_edits(raw.get("edits", {}), root)
        files = []
        for edit in changed:
            if apply:
                write_document(edit["path"], edit["after"])
            files.append(
                {
                    "path": relative_path(edit["path"].as_uri(), root),
                    "edits": len(raw["edits"].get(edit["path"].as_uri(), [])) or None,
                    "linesChanged": changed_line_count(edit["before"], edit["after"]),
                }
            )
        result: dict[str, Any] = {"renamed": apply, "files": files[:cap], "fileCount": len(files)}
        if refused:
            result["skipped"] = [relative_path(uri, root) for uri in refused][:cap]
        return result

    return {
        "explain": explain,
        "review": review,
        "fix": fix,
        "rename": rename,
        "profile_status": profile_status,
    }


def build_tools(
    root: Path, call: Call, output: Mapping[str, Any], environ: Mapping[str, str]
) -> dict[str, Callable[..., Any]]:
    """The tool functions, keyed by their MCP names, bound to one workspace root and one engine session."""
    paged, cap = pager(output)
    clip_at = int(output.get("maxMessageChars", MESSAGE_CHARS))
    return {
        **navigation_tools(root, call, paged, cap),
        **insight_tools(root, call, paged, cap),
        **mcp_search.search_tools(
            root, call, paged, cap, settings=output, max_chars=int(output.get("maxBytes", 16000))
        ),
        **quality_tools(root, call, paged, cap, environ, clip_at=clip_at),
        **practice_tools(root, call, cap, clip_at),
    }


# name: (title, read only). Every tool is closed-world (only this workspace) and idempotent.
TOOL_INFO: dict[str, tuple[str, bool]] = {
    "find_symbol": ("Find Groovy declarations", True),
    "goto_definition": ("Go to definition", True),
    "type_definition": ("Go to type definition", True),
    "find_references": ("Find references", True),
    "hover": ("Hover information", True),
    "document_symbols": ("Document outline", True),
    "call_graph": ("Call graph", True),
    "impact_of": ("Impact of a change", True),
    "check": ("Check findings", True),
    "review": ("Review changed files", True),
    "explain": ("Explain a finding code", True),
    "profile_status": ("Runtime profile of a file", True),
    "find_dynamic_code": ("Find dynamic Groovy", True),
    "workspace_status": ("Workspace and daemon status", True),
    "format": ("Format files", False),
    "fix": ("Apply safe fixes", False),
    "rename": ("Rename a symbol", False),
    "read_symbol": ("Read one declaration", True),
    "find_code": ("Structural code search", True),
    "find_duplicates": ("Find duplicated code", True),
    "workspace_overview": ("Workspace overview", True),
}


def compact_json(value: Any) -> str:
    """JSON without indentation or spaces after separators: the SDK's default pretty printing costs about a
    third more characters, and every character reaches the agent's context."""
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False, default=str)


def respond(function: Callable[..., Any], *, structured: bool) -> Callable[..., Any]:
    """Return a tool result as one compact JSON text block; with ``output.structured`` also as structuredContent
    (for clients that read it; most send the text to the model, so it is off by default to avoid doubling)."""

    @functools.wraps(function)
    def wrapper(*args: Any, **kwargs: Any) -> CallToolResult:
        result = function(*args, **kwargs)
        return CallToolResult(
            content=[TextContent(type="text", text=compact_json(result))],
            structured_content=result if structured and isinstance(result, dict) else None,
        )

    return wrapper


def build_server(root: Path, call: Call, output: Mapping[str, Any], environ: Mapping[str, str]) -> MCPServer:
    """An MCP server exposing the tools of :func:`build_tools` with titles, behaviour annotations and compact
    results."""
    server = MCPServer(SERVER_NAME, instructions=INSTRUCTIONS)
    structured = bool(output.get("structured", False))
    for name, function in build_tools(root, call, output, environ).items():
        title, read_only = TOOL_INFO.get(name, (name, True))
        annotations = ToolAnnotations(
            title=title,
            read_only_hint=read_only,
            destructive_hint=False,
            idempotent_hint=True,
            open_world_hint=False,
        )
        tool = respond(diagnostics.timed(name, function, LOG), structured=structured)
        server.tool(
            name=name, title=title, description=function.__doc__, annotations=annotations, structured_output=False
        )(tool)
    return server


def session_caller(
    socket_file: Path,
    reconnect: Callable[[], Path] | None = None,
    *,
    open_session: Callable[[Path], rpc.Session] | None = None,
) -> tuple[Call, Callable[[], None]]:
    """A ``call`` bound to one engine session that, when the engine has gone away (daemon stopped, JVM killed,
    upgraded), restarts it through ``reconnect`` and retries the request once. Also returns a closer."""
    opener = rpc.open_session if open_session is None else open_session
    state: dict[str, Any] = {"session": opener(socket_file)}
    # The SDK runs tools on worker threads and agents call tools in parallel; one session carries one exchange
    # at a time, so calls queue here instead of interleaving their frames.
    lock = threading.Lock()

    def call(method: str, params: dict[str, Any]) -> Any:
        with lock:
            try:
                return rpc.call(state["session"], method, params, timeout=600.0)
            except OSError as exc:
                if reconnect is None:
                    raise
                LOG.info("engine session lost (%s); reconnecting", exc)
                rpc.close_session(state["session"])
                state["session"] = opener(reconnect())
                return rpc.call(state["session"], method, params, timeout=600.0)

    def close() -> None:
        rpc.close_session(state["session"])

    return (call, close)


def serve(
    root: Path,
    socket_file: Path,
    output: Mapping[str, Any],
    environ: Mapping[str, str],
    reconnect: Callable[[], Path] | None = None,
) -> int:
    """Open one engine session, run the MCP server on stdio until the client disconnects, close the session."""
    # No LSP initialize: every groovyscope/* request names its root, and an initialized session would make
    # the engine lint the whole workspace and push diagnostics for every file to a client that ignores them.
    call, close = session_caller(socket_file, reconnect)
    try:
        build_server(root, call, output, environ).run(transport="stdio")
    finally:
        close()
    return 0
