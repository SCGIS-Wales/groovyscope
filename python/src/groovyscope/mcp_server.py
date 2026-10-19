"""The MCP server: higher-order tools over one persistent JSON-RPC session with the engine daemon.

Every tool returns compact JSON (relative paths, line:col, kinds, pagination) and never a file body.
"""

from __future__ import annotations

import functools
import inspect
import json
import logging
import threading
import time
from collections.abc import Callable, Mapping, Sequence
from concurrent import futures
from concurrent.futures import Future, ThreadPoolExecutor
from pathlib import Path
from typing import Any, TypedDict

from mcp.server.mcpserver import Context, MCPServer
from mcp.types import CallToolResult, TextContent, ToolAnnotations

from groovyscope import (
    changes,
    daemon,
    diagnostics,
    interaction,
    mcp_search,
    mcp_understanding,
    practices,
    reports,
    rpc,
    skills,
    verify,
)
from groovyscope import classpath as classpath_export
from groovyscope import test_results as test_results_module
from groovyscope.edits import (
    FileChangedError,
    FileEdit,
    changed_line_count,
    counts_by_path,
    file_edits,
    uri_path,
    write_document,
)
from groovyscope.kiro import write_atomically
from groovyscope.mcp_server_support import (
    Call,
    Paged,
    clip,
    missing_paths,
    missing_paths_answer,
    relative_path,
    to_uri,
)
from groovyscope.render import page
from groovyscope.tools_catalog import TOOL_INFO, description

SERVER_NAME = "groovyscope"
LOG = logging.getLogger("groovyscope.mcp")
INSTRUCTIONS = (
    "Groovy code intelligence for this workspace; use these tools instead of grep or reading whole files. Start "
    "with workspace_overview, then document_symbols and read_symbol. Navigation answers carry a confidence "
    "(DYNAMIC_UNKNOWN: matched by name only). Lists are paged: pass limit and next_cursor. Paths are "
    "workspace-relative, lines and columns 1-based. Rename only through rename. Before finishing a change, call "
    "review on the changed files and fix what it reports. GS100 means no classpath: suggest `groovyscope classpath`. "
    "Call profile_status before editing a file a runtime profile governs. The groovy-* prompts hold the recipes."
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


def summarise(
    findings: list[dict[str, Any]], codes: Sequence[str] | None = None, max_priority: int | None = None
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """The findings CODES and MAX_PRIORITY select, without the per-file missing-dependency notes, plus counts per
    code and a one-line summary of the files that miss dependencies (always reported; fix: `groovyscope
    classpath`)."""
    missing = [f for f in findings if f.get("code") == "GS100"]
    rest = filter_findings([f for f in findings if f.get("code") != "GS100"], codes, max_priority)
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


def imported_findings(
    root: Path, report_settings: Mapping[str, Any], paths: Sequence[str] | None
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Findings from the build's SARIF logs (SpotBugs and other tools), limited to PATHS when given, plus an
    ``imported`` summary (counts per tool and the files that could not be read)."""
    findings, notes = reports.merge_sarif(root, report_settings.get("sarif") or [])
    wanted = [p.rstrip("/") for p in paths or []]
    if wanted:
        findings = [f for f in findings if any(f["path"] == w or f["path"].startswith(w + "/") for w in wanted)]
    counts: dict[str, int] = {}
    for finding in findings:
        counts[str(finding["source"])] = counts.get(str(finding["source"]), 0) + 1
    summary: dict[str, Any] = {}
    if counts:
        summary["imported"] = counts
    if notes:
        summary["importNotes"] = notes
    return (findings, summary)


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

    def symbol_context(path: str, line: int, col: int) -> dict[str, Any]:
        """The definition, the hover text and how many places use the symbol at PATH:LINE:COL (1-based), in one
        call: what an agent otherwise asks goto_definition, hover and find_references for, one after another.
        The reference count is split by confidence; find_references lists them when they are needed."""
        params = position_params(root, path, line, col)
        found = goto_definition(path, line, col)
        raw_hover = call("groovyscope/hover", params) or {}
        raw_refs = call("groovyscope/references", {**params, "includeDeclaration": False}) or {}
        references = raw_refs.get("references", [])
        by_confidence: dict[str, int] = {}
        for item in references:
            key = str(item.get("confidence") or "UNKNOWN")
            by_confidence[key] = by_confidence.get(key, 0) + 1
        return {
            "found": bool(found.get("found")),
            "symbol": found.get("symbol"),
            "confidence": found.get("confidence"),
            "hover": clip(raw_hover.get("markdown", ""), cap * 40) if raw_hover else None,
            "references": {"total": len(references), "byConfidence": by_confidence},
        }

    return {
        "find_symbol": find_symbol,
        "goto_definition": goto_definition,
        "find_references": find_references,
        "call_graph": call_graph,
        "impact_of": impact_of,
        "symbol_context": symbol_context,
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
    root: Path,
    call: Call,
    paged: Paged,
    cap: int,
    environ: Mapping[str, str],
    *,
    clip_at: int = MESSAGE_CHARS,
    report_settings: Mapping[str, Any] | None = None,
    classpath_settings: Mapping[str, Any] | None = None,
    confirm_policy: str = interaction.POLICY_AUTO,
) -> dict[str, Callable[..., Any]]:
    """check, format, find_dynamic_code, coverage_gaps, refresh_classpath and workspace_status."""
    settings = report_settings or {}
    classpath = classpath_settings or {}
    export_job = new_export_job()

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
        imported, imported_summary = imported_findings(root, settings, paths)
        findings, extra = summarise([*findings, *imported], codes, max_priority)
        findings.sort(key=lambda f: (SEVERITY_ORDER.get(f["severity"], 9), f["path"], f["line"]))
        result = paged(findings, limit, cursor)
        result["summary"] = raw.get("summary", {})
        result.update(extra)
        result.update(imported_summary)
        state = classpath_state(root, classpath)
        if state is not None and state["state"] in ATTENTION_STATES:
            result["classpath"] = state
        return result

    def format_files(paths: list[str] | None = None, apply: bool = False) -> dict[str, Any]:
        """Formatting edits for PATHS (whole workspace when empty). APPLY writes the files; otherwise only counts."""
        raw = call("groovyscope/format", {"root": str(root), "uris": [to_uri(root, p) for p in paths or []]})
        changed, refused = file_edits(raw.get("edits", {}), root)
        decision = interaction.UNSUPPORTED
        if apply and changed:
            decision = confirm_edits(confirm_policy, f"Format {len(changed)} file(s)", changed, root)
            apply = not declined(decision)
        files = []
        for edit in changed:
            if apply and not write_if_unchanged(edit, refused):
                continue
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
        return with_decision(result, decision)

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
        """Daemon, engine version, root, file count, trust flag, index state and whether the classpath still
        matches the build (fresh, stale, missing, configured, none)."""
        status = call("groovyscope/status", {"root": str(root)})
        info = daemon.info(root, environ)
        result: dict[str, Any] = {
            "root": str(root),
            "daemon": {"socket": info["socket"], "running": info["running"], "log": info["log"]},
            "engine": status,
        }
        result.update(client_entry())
        result.update(classpath_report(root, classpath, export_job))
        return result

    return {
        "check": check,
        "format": format_files,
        "find_dynamic_code": find_dynamic_code,
        "workspace_status": workspace_status,
        **coverage_tools(root, paged, settings),
        **classpath_tools(root, environ, classpath, clip_at=clip_at, confirm_policy=confirm_policy, job=export_job),
    }


# How many inline merge request comments review_changes drafts per unit of `top`.
COMMENTS_PER_TOP = 3
# Classpath states worth a line in a check or review answer; fresh, configured and none need no action.
ATTENTION_STATES = frozenset({"stale", "missing"})


def classpath_state(root: Path, settings: Mapping[str, Any]) -> dict[str, Any] | None:
    """Whether the classpath the engine resolves against still matches the build files (stat calls only), or
    None when the front end gave no classpath settings."""
    file = settings.get("file")
    if not file:
        return None
    try:
        return dict(classpath_export.status(root, Path(str(file)), configured=bool(settings.get("configured"))))
    except OSError as exc:
        return {"state": "unknown", "entries": 0, "message": f"classpath file unreadable: {exc}"}


class ExportJob(TypedDict):
    """The classpath export the MCP server runs in the background: the build outlives one tool call's budget."""

    lock: threading.Lock
    thread: threading.Thread | None
    started: float
    outcome: dict[str, Any] | None
    reported: bool


DEFAULT_EXPORT_WAIT_SECONDS = 90.0


def new_export_job() -> ExportJob:
    return {"lock": threading.Lock(), "thread": None, "started": 0.0, "outcome": None, "reported": True}


def export_status(job: ExportJob, clock: Callable[[], float] = time.monotonic) -> dict[str, Any] | None:
    """``running`` with the seconds so far, the last outcome not yet read, or None when no export ran."""
    with job["lock"]:
        thread, outcome = job["thread"], job["outcome"]
        if thread is not None and thread.is_alive():
            return {"state": "running", "elapsedSeconds": round(clock() - job["started"])}
        if outcome is None:
            return None
        return {"state": "failed" if "error" in outcome else "finished", **outcome}


def classpath_report(root: Path, settings: Mapping[str, Any], job: ExportJob) -> dict[str, Any]:
    """workspace_status's ``classpath`` (does it still match the build) and ``classpathExport`` (a background
    export running, or its outcome), each only when there is something to say."""
    report: dict[str, Any] = {}
    state = classpath_state(root, settings)
    if state is not None:
        report["classpath"] = state
    export = export_status(job)
    if export is not None:
        report["classpathExport"] = export
    return report


def still_running(job: ExportJob, clock: Callable[[], float] = time.monotonic) -> dict[str, Any]:
    return {
        "state": "running",
        "elapsedSeconds": round(clock() - job["started"]),
        "note": "the build still runs in the background; call refresh_classpath again, or workspace_status, for its "
        "result (check keeps working against the previous classpath meanwhile)",
    }


def classpath_tools(
    root: Path,
    environ: Mapping[str, str],
    settings: Mapping[str, Any],
    *,
    clip_at: int = MESSAGE_CHARS,
    confirm_policy: str = interaction.POLICY_AUTO,
    job: ExportJob | None = None,
) -> dict[str, Callable[..., Any]]:
    """refresh_classpath: the build's classpath exported again after dependencies changed, in the background, so
    the tool answers within its budget however long the build runs."""
    export_job = job if job is not None else new_export_job()
    limit = max(1, int(settings.get("timeoutSeconds") or classpath_export.DEFAULT_TIMEOUT_SECONDS))
    wait_for = max(0.0, float(settings.get("waitSeconds", DEFAULT_EXPORT_WAIT_SECONDS)))
    every = max(0.5, float(settings.get("progressSeconds", DEFAULT_PROGRESS_SECONDS)))

    def refresh_classpath(timeout: int | None = None) -> dict[str, Any]:
        """Export the compile and test classpath again from the project's Gradle or Maven build, after a
        dependency changed in build.gradle, pom.xml or libs.versions.toml (check and workspace_status say
        `classpath.state: stale`); the engine picks the new classpath up on its next request, no restart. Runs
        the build, which can take minutes, and only in a trusted workspace: otherwise ask the user to run
        `groovyscope trust` and `groovyscope classpath`. When the build outlasts this call the answer says
        `state: running`; call again (or workspace_status) for the result. TIMEOUT is capped at
        classpath.timeoutSeconds."""
        with export_job["lock"]:
            thread = export_job["thread"]
            if thread is not None and thread.is_alive():
                return still_running(export_job)
            if export_job["outcome"] is not None and not export_job["reported"]:
                export_job["reported"] = True  # the export the agent started earlier finished: its result
                return dict(export_job["outcome"])
        if not settings.get("file"):
            return {"error": "no classpath settings in this session; run `groovyscope classpath` in a terminal"}
        # Read now, not when the session started: `groovyscope trust --revoke` takes effect at once.
        check_trust = settings.get("isTrusted")
        trusted = bool(check_trust()) if callable(check_trust) else bool(settings.get("trusted"))
        if not trusted:
            return {
                "error": "refused: the export runs the project's build scripts, which only a trusted workspace "
                "may do; ask the user to run `groovyscope trust` and then `groovyscope classpath`"
            }
        build = classpath_export.detect_tool(root) or "build"
        decision = interaction.confirm(
            f"Run the {build} build of {root.name} to export its classpath? It runs the project's build scripts "
            "and can take minutes.",
            policy=confirm_policy,
        )
        if declined(decision):
            return {"refreshed": False, "confirmation": decision, "reason": "nothing was run: the user said no"}
        env = dict(environ)
        if not env.get("JAVA_HOME") and settings.get("javaHome"):
            env["JAVA_HOME"] = str(settings["javaHome"])
        target = Path(str(settings["file"]))
        seconds = min(limit, max(1, int(timeout))) if timeout else limit

        def run() -> None:
            outcome = export_outcome(root, env, target, seconds, decision=decision, clip_at=clip_at)
            with export_job["lock"]:
                export_job["outcome"] = outcome

        worker = threading.Thread(target=run, name="groovyscope-classpath-export", daemon=True)
        with export_job["lock"]:
            export_job["thread"] = worker
            export_job["started"] = time.monotonic()
            export_job["outcome"] = None
            export_job["reported"] = False
        interaction.progress(0, None, f"starting the {build} build")
        worker.start()
        return await_export(export_job, worker, wait_for, every, build)

    return {"refresh_classpath": refresh_classpath}


def export_outcome(
    root: Path, env: Mapping[str, str], target: Path, seconds: int, *, decision: str, clip_at: int
) -> dict[str, Any]:
    """Runs the export under its lock and writes the file; the answer refresh_classpath gives for it."""
    try:
        with classpath_export.exclusive(target):
            export = classpath_export.export(
                root,
                env,
                timeout=seconds,
                # the build runs on its own thread; the caller's thread reports progress while it waits
                runner=functools.partial(classpath_export.default_runner, on_wait=lambda _elapsed: None),
            )
            classpath_export.write_file(target, export, fingerprint=classpath_export.build_fingerprint(root))
    except ValueError as exc:
        return {"error": clip(str(exc), clip_at)}
    except OSError as exc:
        return {"error": f"the classpath could not be written to {target}: {exc}"}
    return with_decision(
        {
            "tool": export["tool"],
            "entries": len(export["paths"]),
            "missing": len(export["missing"]),
            "file": relative_path(target.resolve().as_uri(), root),
            "note": "the engine reloads the classpath on its next request",
        },
        decision,
    )


def await_export(job: ExportJob, worker: threading.Thread, wait_for: float, every: float, build: str) -> dict[str, Any]:
    """Waits up to ``wait_for`` seconds for the export, with a progress note every ``every`` seconds; its outcome
    when it finished, else "still running" (the build goes on and a later call or workspace_status reports it)."""
    deadline = time.monotonic() + wait_for
    while worker.is_alive() and time.monotonic() < deadline:
        worker.join(min(every, max(0.0, deadline - time.monotonic())))
        if worker.is_alive():
            elapsed = time.monotonic() - job["started"]
            interaction.progress(elapsed, None, f"{build} build running ({elapsed:.0f}s)")
    with job["lock"]:
        if worker.is_alive() or job["outcome"] is None:
            return still_running(job)
        job["reported"] = True
        return dict(job["outcome"])


def coverage_tools(root: Path, paged: Paged, settings: Mapping[str, Any]) -> dict[str, Callable[..., Any]]:
    """coverage_gaps: the build's JaCoCo report as the list of what tests never executed."""

    def coverage_gaps(paths: list[str] | None = None, limit: int | None = None, cursor: int = 0) -> dict[str, Any]:
        """Classes and methods with lines no test executed, from the build's JaCoCo XML report (reports.jacoco),
        most missed lines first, under PATHS when given; plus the overall line coverage. Run the build's test task
        with jacocoTestReport first. Use it to decide which tests to write."""
        report = reports.jacoco_file(root, settings.get("jacoco"))
        if report is None:
            expected = settings.get("jacoco") or reports.DEFAULT_JACOCO
            return {"error": f"no JaCoCo report at {expected}; run the build's tests with jacocoTestReport first"}
        try:
            gaps = reports.filter_gaps(reports.read_jacoco(report, root), paths or [])
            summary = reports.coverage_summary(report)
        except ValueError as exc:
            return {"error": str(exc)}
        result = paged([dict(g) for g in gaps], limit, cursor)
        result["count"] = len(gaps)
        result["report"] = relative_path(report.as_uri(), root)
        result["coverage"] = summary
        result["uncoveredMethods"] = sum(1 for g in gaps if g["kind"] == "method" and g["status"] == "uncovered")
        return result

    return {"coverage_gaps": coverage_gaps}


def test_tools(root: Path, cap: int) -> dict[str, Callable[..., Any]]:
    """test_results: the JUnit XML reports of the last test run (or of a CI archive), summarised."""

    def test_results(sources: list[str] | None = None, limit: int | None = None) -> dict[str, Any]:
        """Failing tests from TEST-*.xml at any depth under SOURCES (folders or .zip archives; default the
        workspace): message and the first frames in the code and the test."""
        summary = test_results_module.summarise(test_results_module.default_sources(root, sources))
        shown = max(1, min(limit or cap, cap))
        return {**summary, "failed": summary["failed"][:shown], "failedCount": len(summary["failed"])}

    return {"test_results": test_results}


def fix_request(
    root: Path, paths: Sequence[str] | None, codes: Sequence[str] | None, *, changed_only: bool, base: str | None
) -> tuple[dict[str, Any], dict[str, Any] | None]:
    """The ``groovyscope/fix`` params, limited to the branch's changed lines with ``changed_only``; or the answer to
    give at once (no git, nothing changed)."""
    params: dict[str, Any] = {
        "root": str(root),
        "uris": [to_uri(root, p) for p in paths or []],
        "codes": list(codes or []),
    }
    if not changed_only:
        return (params, None)
    try:
        scope = changes.fix_scope(root, changes.changed_files(root, base))
    except ValueError as exc:
        return (params, {"error": str(exc)})
    if not scope["uris"]:
        return (params, {"changed": 0, "files": [], "rejected": {}, "note": "no changed Groovy lines to fix"})
    return ({**params, **scope}, None)


def unfixed(raw: Mapping[str, Any], root: Path, cap: int) -> dict[str, Any]:
    """What a fix left: findings with no safe fix (``left``) and fixes that change behaviour (``needsReview``)."""
    answer: dict[str, Any] = {}
    for key, note in (("left", LEFT_NOTE), ("needsReview", NEEDS_REVIEW_NOTE)):
        counts = counts_by_path(raw.get(key, {}))
        if counts:
            answer[key] = {relative_path(path.as_uri(), root): c for path, c in list(counts.items())[:cap]}
            answer[f"{key}Note"] = note
    return answer


def practice_tools(
    root: Path,
    call: Call,
    cap: int,
    clip_at: int = MESSAGE_CHARS,
    report_settings: Mapping[str, Any] | None = None,
    *,
    confirm_policy: str = interaction.POLICY_AUTO,
) -> dict[str, Callable[..., Any]]:
    """explain, review, fix and profile_status."""
    settings = report_settings or {}

    def explain(code: str) -> dict[str, Any]:
        """What a diagnostic code means (GS001-GS016, a Groovy 6 checker, a CodeNarc rule), why it matters, the fix."""
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
        imported, imported_summary = imported_findings(root, settings, paths)
        findings, extra = summarise([*findings, *imported], None, max_priority)
        groups = practices.review(findings, max(1, min(top, cap)))
        result: dict[str, Any] = {
            "summary": raw.get("summary", {}),
            "groups": groups[:cap],
            **extra,
            **imported_summary,
        }
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

    def fix(
        paths: list[str] | None = None,
        codes: list[str] | None = None,
        apply: bool = False,
        changed_only: bool = False,
        base: str | None = None,
    ) -> dict[str, Any]:
        """Safe fixes for PATHS (all when empty) and CODES (all fixable when empty). APPLY writes the files.
        CHANGED_ONLY limits them to the lines this branch changed against BASE (default: the repository's default
        branch), so existing code is left as it is."""
        params, early = fix_request(root, paths, codes, changed_only=changed_only, base=base)
        if early is not None:
            return early
        raw = call("groovyscope/fix", params)
        changed, refused = file_edits(raw.get("edits", {}), root)
        decision = interaction.UNSUPPORTED
        if apply and changed:
            decision = confirm_edits(confirm_policy, f"Apply safe fixes to {len(changed)} file(s)", changed, root)
            apply = not declined(decision)
        fixed = counts_by_path(raw.get("appliedCounts", {}))
        files: list[dict[str, Any]] = []
        for edit in changed:
            if apply and not write_if_unchanged(edit, refused):
                continue
            uri = edit["path"].as_uri()
            entry: dict[str, Any] = {
                "path": relative_path(uri, root),
                "codes": next((v for k, v in raw.get("applied", {}).items() if uri_path(k) == edit["path"]), []),
                "linesChanged": changed_line_count(edit["before"], edit["after"]),
                "applied": apply,
            }
            if edit["path"] in fixed:
                entry["fixed"] = fixed[edit["path"]]
            files.append(entry)
        rejected = {
            **{relative_path(uri, root): why for uri, why in raw.get("rejected", {}).items()},
            **{relative_path(uri, root): "not a Groovy source inside the workspace" for uri in refused},
        }
        answer: dict[str, Any] = {"changed": len(files), "files": files[:cap], "rejected": rejected}
        answer.update(unfixed(raw, root, cap))
        return with_decision(answer, decision)

    return {
        "explain": explain,
        "review": review,
        "fix": fix,
        "profile_status": profile_status,
        **rename_tools(root, call, cap, confirm_policy=confirm_policy),
    }


def rename_tools(
    root: Path, call: Call, cap: int, *, confirm_policy: str = interaction.POLICY_AUTO
) -> dict[str, Callable[..., Any]]:
    """rename: refused on name-only matches, applied atomically, verified by a recompile, rolled back on new errors."""

    def rename(path: str, line: int, col: int, new_name: str, apply: bool = False) -> dict[str, Any]:
        """Rename the symbol at PATH:LINE:COL (1-based) to NEW_NAME everywhere it is used. Refused when any use is
        matched by name only (dynamic Groovy). A dry run listing the edits per file unless APPLY."""
        raw = call("groovyscope/rename", {**position_params(root, path, line, col), "newName": new_name})
        if raw.get("refused"):
            return {"renamed": False, "refused": raw["refused"]}
        changed, refused = file_edits(raw.get("edits", {}), root)
        files = [
            {
                "path": relative_path(edit["path"].as_uri(), root),
                "edits": len(raw["edits"].get(edit["path"].as_uri(), [])) or None,
                "linesChanged": changed_line_count(edit["before"], edit["after"]),
            }
            for edit in changed
        ]
        decision = interaction.UNSUPPORTED
        if apply and changed:
            decision = confirm_edits(confirm_policy, f"Rename to {new_name} in {len(changed)} file(s)", changed, root)
            apply = not declined(decision)
        result: dict[str, Any] = {"renamed": apply, "files": files[:cap], "fileCount": len(files)}
        if refused:
            result["skipped"] = [relative_path(uri, root) for uri in refused][:cap]
        if apply and changed:
            result.update(apply_verified(changed))
        return with_decision(result, decision)

    def apply_verified(changed: list[FileEdit]) -> dict[str, Any]:
        """Write the edits, recompile the changed files, and restore every file when a write fails, the engine
        cannot verify, or the rename left new compile errors: the workspace is either fully renamed or untouched."""
        uris = [edit["path"].as_uri() for edit in changed]
        try:
            errors_before = compile_errors(uris)
        except (OSError, RuntimeError) as exc:
            return {"renamed": False, "reason": f"nothing written: the engine could not compile first ({exc})"}
        written: list[FileEdit] = []
        try:
            for edit in changed:
                write_document(edit["path"], edit["after"], expected=edit["before"])
                written.append(edit)
            errors_after = compile_errors(uris)
        except (OSError, RuntimeError) as exc:
            failed = restore(written)
            reason = f"{exc}; the files already changed were restored"
            if failed:
                reason += f", except {', '.join(failed)}: restore them from version control"
            return {"renamed": False, "rolledBack": True, "reason": reason}
        if errors_after > errors_before:
            failed = restore(written)
            reason = (
                f"the rename left {errors_after - errors_before} new compile error(s); the files were restored. "
                "Check the symbol (a same-named member in another class?) or rename by hand"
            )
            if failed:
                reason += f". Restore {', '.join(failed)} from version control"
            return {"renamed": False, "rolledBack": True, "reason": reason}
        return {"verified": "the changed files compile with no new errors"}

    def restore(written: list[FileEdit]) -> list[str]:
        """Put the original text back; the paths that could not be restored."""
        failed: list[str] = []
        for edit in written:
            try:
                write_document(edit["path"], edit["before"], expected=edit["after"])
            except OSError:
                failed.append(relative_path(edit["path"].as_uri(), root))
        return failed

    def compile_errors(uris: list[str]) -> int:
        """Compile errors in the given documents, as the engine reports them after re-reading the files."""
        raw = call("groovyscope/check", {"root": str(root), "uris": uris, "include": ["compile"]})
        return sum(
            1 for items in raw.get("diagnostics", {}).values() for item in items if int(item.get("severity", 3)) == 1
        )

    return {"rename": rename}


def flat_outline(raw: Mapping[str, Any]) -> list[dict[str, Any]]:
    """Every declaration of a document outline as ``{name, kind, lines, in}`` with 1-based inclusive lines."""
    flat: list[dict[str, Any]] = []

    def walk(item: Mapping[str, Any], container: str | None) -> None:
        location = item.get("location") or {}
        start = int((location.get("range") or {}).get("start", {}).get("line", 0)) + 1
        end = int(item.get("endLine", start - 1)) + 1
        kind = str(item.get("kind") or "").lower()
        if kind.isdigit():
            kind = LSP_SYMBOL_KINDS.get(int(kind), kind)
        entry = {"name": item.get("name"), "kind": kind, "lines": [start, max(start, end)]}
        if container:
            entry["in"] = container
        flat.append(entry)
        for child in item.get("children") or []:
            walk(child, str(item.get("name")))

    for item in raw.get("symbols", []):
        walk(item, None)
    return flat


def impact_target(declaration: Mapping[str, Any]) -> str:
    """``Class#member`` for a member, the qualified name for a type: what ``impact_of`` takes."""
    container, _, member = str(declaration["symbol"]).rpartition(".")
    if container and declaration["kind"] not in {"class", "interface", "trait", "enum", "record"}:
        return f"{container}#{member}"
    return str(declaration["symbol"])


def review_tools(
    root: Path,
    call: Call,
    cap: int,
    clip_at: int,
    *,
    others: Mapping[str, Callable[..., Any]],
    report_settings: Mapping[str, Any] | None = None,
    verify_settings: Mapping[str, Any] | None = None,
) -> dict[str, Callable[..., Any]]:
    """review_changes: the merge request review, composed from git, the build's reports and the other tools."""
    settings = report_settings or {}

    def review_changes(base: str | None = None, staged: bool = False, top: int = 8) -> dict[str, Any]:
        """Review what this branch changed against BASE (default: the repository's default branch, from origin/HEAD)
        or the index with STAGED, as a merge request reviewer:
        the changed Groovy, Gradle and Jenkinsfile files, every finding on their changed lines grouped blocking,
        shouldFix and nits, pre-existing findings elsewhere in those files as counts, the impact of changed
        declarations (callers, tests, risk), risky constructs the change added (catch, def, return null), a
        `triage` that ranks the changed-line findings into risks, smells and noise with how many the safe fixes
        repair and the `groovyscope fix` command, `tests`: the test methods the change reaches (through the call
        graph, Java tests included) with the Gradle command that compiles and runs them (`groovyscope verify` runs
        it), and a one-sentence verdict, plus `comments` (path, line, body)
        ready to post as inline merge request discussions and a `summaryComment` for the merge request itself.
        Nothing is run but git."""
        try:
            change = changes.changed_files(root, base, staged=staged)
        except ValueError as exc:
            return {"error": str(exc)}
        existing = [f for f in change["files"] if f["status"] != "deleted"]
        shown = [
            {"path": f["path"], "status": f["status"], "changedLines": sum(e - s + 1 for s, e in f["ranges"])}
            for f in change["files"]
        ]
        result: dict[str, Any] = {"base": change["base"], "mode": change["mode"], "files": shown[:cap]}
        if not existing:
            result["verdict"] = (
                f"nothing to review: no Groovy, Java, Gradle or Jenkinsfile changes against {change['base']}"
            )
            return result
        ranges = changes.ranges_by_path(change)
        paths = [f["path"] for f in existing]
        raw = call("groovyscope/check", {"root": str(root), "uris": [to_uri(root, p) for p in paths], "include": []})
        findings = [
            compact_diagnostic(uri, item, root, clip_at)
            for uri, items in raw.get("diagnostics", {}).items()
            for item in items
        ]
        imported, imported_summary = imported_findings(root, settings, paths)
        findings, extra = summarise([*findings, *imported])
        inside, elsewhere = changes.split_findings(findings, ranges)
        buckets = changes.bucket(inside)
        limit = max(1, min(top, cap))
        impact: list[dict[str, Any]] = []
        reports: list[dict[str, Any]] = []  # every changed declaration's impact, for the tests the change reaches
        touched: list[dict[str, Any]] = []  # changed declarations (changed test methods run one by one)
        for file in existing[:cap]:
            if len(reports) >= verify.MAX_DECLARATIONS:
                break
            outline = flat_outline(
                call("groovyscope/documentSymbols", {"root": str(root), "uri": to_uri(root, file["path"])})
            )
            for declaration in changes.touched_declarations(outline, file["ranges"], limit=verify.MAX_DECLARATIONS):
                touched.append({**declaration, "path": file["path"]})
                if len(reports) >= verify.MAX_DECLARATIONS:
                    break
                report = others["impact_of"](impact_target(declaration), "signature")
                if not report.get("found"):
                    continue
                first = report["reports"][0]
                reports.append(first)
                if len(impact) >= limit:
                    continue
                impact.append(
                    {
                        **declaration,
                        "path": file["path"],
                        "riskLevel": first.get("riskLevel"),
                        "riskScore": first.get("riskScore"),
                        "callers": first.get("counts", {}),
                        "tests": len(first.get("tests", [])),
                        "dynamicSites": len(first.get("dynamicUnknowns", [])),
                    }
                )
        risky: list[dict[str, Any]] = []
        for kind in changes.RISKY_KINDS:
            matches = others["find_code"](kind, paths=paths).get("items", [])
            risky += [{"kind": kind, **m} for m in changes.risky_in_change(matches, ranges)]
        elsewhere_counts: dict[str, int] = {}
        for finding in elsewhere:
            elsewhere_counts[str(finding.get("code"))] = elsewhere_counts.get(str(finding.get("code")), 0) + 1
        coverage = others["coverage_gaps"](paths)
        uncovered = (
            [g for g in coverage.get("items", []) if g["kind"] == "method" and in_change(g, ranges)]
            if "error" not in coverage
            else []
        )
        result.update(
            {
                "verdict": changes.verdict(buckets, risky, impact),
                "triage": changes.triage(inside, limit=limit),
                "tests": verify.plan(root, reports, paths, verify_settings or {}, limit=limit, touched=touched),
                "blocking": buckets["blocking"][:limit],
                "shouldFix": buckets["shouldFix"][:limit],
                "nits": buckets["nits"][:limit],
                "counts": {name: len(items) for name, items in buckets.items()},
                "elsewhere": {
                    "count": len(elsewhere),
                    "byCode": dict(sorted(elsewhere_counts.items(), key=lambda kv: -kv[1])[:limit]),
                },
                "impact": impact,
                "risky": risky[:limit],
                "riskyCount": len(risky),
                "uncovered": uncovered[:limit],
                "uncoveredCount": len(uncovered),
                "classpath": others["workspace_status"]().get("classpath") if "workspace_status" in others else None,
                **extra,
                **imported_summary,
            }
        )
        comments = changes.draft_comments(buckets, risky[:limit], uncovered[:limit], limit=limit * COMMENTS_PER_TOP)
        result["comments"] = comments
        result["summaryComment"] = changes.summary_comment(
            result["verdict"], result["counts"], impact, len(uncovered), len(comments)
        )
        return result

    def in_change(gap: Mapping[str, Any], ranges: Mapping[str, Sequence[Sequence[int]]]) -> bool:
        return changes.in_ranges(int(gap.get("line", 0)), ranges.get(str(gap.get("path")), ()))

    return {"review_changes": review_changes}


def build_tools(
    root: Path, call: Call, output: Mapping[str, Any], environ: Mapping[str, str]
) -> dict[str, Callable[..., Any]]:
    """The tool functions, keyed by their MCP names, bound to one workspace root and one engine session."""
    paged, cap = pager(output)
    clip_at = int(output.get("maxMessageChars", MESSAGE_CHARS))
    report_settings = output.get("reports") if isinstance(output.get("reports"), Mapping) else {}
    classpath_settings = output.get("classpath") if isinstance(output.get("classpath"), Mapping) else {}
    mcp_settings: Mapping[str, Any] = output.get("mcp") or {}
    policy = str(mcp_settings.get("confirmWrites", interaction.POLICY_AUTO))
    tools: dict[str, Callable[..., Any]] = {
        **navigation_tools(root, call, paged, cap),
        **insight_tools(root, call, paged, cap),
        **mcp_search.search_tools(
            root, call, paged, cap, settings=output, max_chars=int(output.get("maxBytes", 16000))
        ),
        **quality_tools(
            root,
            call,
            paged,
            cap,
            environ,
            clip_at=clip_at,
            report_settings=report_settings,
            classpath_settings=classpath_settings,
            confirm_policy=policy,
        ),
        **practice_tools(root, call, cap, clip_at, report_settings, confirm_policy=policy),
        **test_tools(root, cap),
    }
    verify_settings: Mapping[str, Any] = output.get("verify") or {}
    tools.update(
        review_tools(
            root, call, cap, clip_at, others=tools, report_settings=report_settings, verify_settings=verify_settings
        )
    )
    diagram_settings: Mapping[str, Any] = output.get("diagram") or {}
    shared_callers = int(diagram_settings.get("sharedHelperCallers", 5))
    tools.update(
        mcp_understanding.understanding_tools(
            root, call, cap, tools, confirm_policy=policy, shared_callers=shared_callers
        )
    )
    return tools


def compact_json(value: Any) -> str:
    """JSON without indentation or spaces after separators: the SDK's default pretty printing costs about a
    third more characters, and every character reaches the agent's context."""
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False, default=str)


def respond(
    function: Callable[..., Any],
    *,
    structured: bool,
    max_bytes: int = 0,
    on_client: Callable[[Any], None] | None = None,
) -> Callable[..., Any]:
    """Return a tool result as one compact JSON text block; with ``output.structured`` also as structuredContent
    (for clients that read it; most send the text to the model, so it is off by default to avoid doubling).
    With ``max_bytes`` (``output.maxBytes``) no answer is larger: see :func:`fit`. ``on_client`` sees the request
    context of every call (the server records the client's name and capabilities from the first)."""

    @functools.wraps(function)
    def wrapper(*args: Any, **kwargs: Any) -> CallToolResult:
        # The SDK passes the request context as `ctx` (declared below); the tool itself never sees it, the
        # confirmations and progress notes in `interaction` read it from this worker thread.
        context = kwargs.pop(CONTEXT_PARAMETER, None)
        if on_client is not None and context is not None:
            on_client(context)
        with interaction.bound(context):
            result = fit(function(*args, **kwargs), max_bytes)
        return CallToolResult(
            content=[TextContent(type="text", text=compact_json(result))],
            structured_content=result if structured and isinstance(result, dict) else None,
        )

    signature = inspect.signature(function)
    context_parameter = inspect.Parameter(CONTEXT_PARAMETER, inspect.Parameter.KEYWORD_ONLY, annotation=Context)
    wrapper.__signature__ = signature.replace(  # type: ignore[attr-defined]
        parameters=[*signature.parameters.values(), context_parameter]
    )
    wrapper.__annotations__ = {**getattr(function, "__annotations__", {}), CONTEXT_PARAMETER: Context}
    return wrapper


CONTEXT_PARAMETER = "ctx"


def existing_paths(root: Path, function: Callable[..., Any]) -> Callable[..., Any]:
    """``function`` answering an error, without running, when its ``path`` or ``paths`` name files the workspace
    does not have (see :func:`missing_paths`)."""

    @functools.wraps(function)
    def wrapper(*args: Any, **kwargs: Any) -> Any:
        missing = missing_paths(root, kwargs)
        if missing:
            LOG.info("%s: not in the workspace %s: %s", function.__name__, root, missing)
            return missing_paths_answer(root, missing)
        return function(*args, **kwargs)

    return wrapper


FIT_ROUNDS = 64
FIT_MIN_STRING = 200


def fit(result: Any, max_bytes: int) -> Any:
    """``result`` cut down to ``max_bytes`` of compact JSON, whatever tool produced it: the largest list loses its
    tail half until the answer fits, then the longest strings are clipped. What was cut is said in
    ``truncated`` (path to the number of items dropped) with a note on how to ask for less. The paged tools
    stay well under the cap already; this is the guarantee for every other answer."""
    if max_bytes <= 0 or not isinstance(result, dict) or len(compact_json(result).encode("utf-8")) <= max_bytes:
        return result
    trimmed: dict[str, Any] = json.loads(compact_json(result))
    dropped: dict[str, int] = {}
    for _ in range(FIT_ROUNDS):
        if len(compact_json({**trimmed, "truncated": dropped}).encode("utf-8")) <= max_bytes:
            break
        largest = largest_list(trimmed)
        if largest is not None and len(largest[1]) > 0:
            path, items = largest
            keep = len(items) // 2
            dropped[path] = dropped.get(path, 0) + len(items) - keep
            del items[keep:]
            continue
        if not clip_longest_string(trimmed):
            break
    trimmed["truncated"] = dropped
    trimmed["note"] = "answer cut to output.maxBytes; narrow the paths, filter, or page with limit and cursor"
    return trimmed


def largest_list(value: Any, path: str = "") -> tuple[str, list[Any]] | None:
    """The list to cut next, with its dotted path: the one with the largest JSON form among lists of two or more
    items (so a one-item wrapper keeps its item and the list inside it is cut instead), else the largest
    one-item list."""
    best: tuple[int, int, str, list[Any]] | None = None
    stack: list[tuple[str, Any]] = [(path, value)]
    while stack:
        where, node = stack.pop()
        children: list[tuple[str, Any]] = []
        if isinstance(node, dict):
            children = [(f"{where}.{key}" if where else str(key), child) for key, child in node.items()]
        elif isinstance(node, list):
            if node:
                rank = (1 if len(node) > 1 else 0, len(compact_json(node)))
                if best is None or rank > (best[0], best[1]):
                    best = (rank[0], rank[1], where or "result", node)
            children = [(where, child) for child in node]
        stack.extend(children)
    return None if best is None else (best[2], best[3])


def clip_longest_string(value: Any) -> bool:
    """Halves the longest string over ``FIT_MIN_STRING`` characters in place; False when there is none."""
    best: tuple[int, Any, Any] | None = None
    stack: list[Any] = [value]
    while stack:
        node = stack.pop()
        items = node.items() if isinstance(node, dict) else enumerate(node) if isinstance(node, list) else ()
        for key, child in items:
            if isinstance(child, str) and len(child) > FIT_MIN_STRING and (best is None or len(child) > best[0]):
                best = (len(child), node, key)
            elif isinstance(child, (dict, list)):
                stack.append(child)
    if best is None:
        return False
    _, container, key = best
    text = container[key]
    container[key] = text[: len(text) // 2] + " ..."
    return True


PRACTICES_URI = "groovyscope://practices"


def register_resources(server: MCPServer) -> None:
    """The practices catalogue as a knowledge base: an index of every groovyscope code, and one page per code.
    Kiro lists resources under `#`; a client reads them only when the user or the agent asks, so they cost no
    context until then."""
    server.resource(
        PRACTICES_URI,
        name="practices",
        title="Groovy practices catalogue",
        description="Every GS and RP code with its group, severity and title; read a code's page for the fix",
        mime_type="text/markdown",
    )(practices_index)
    server.resource(
        PRACTICES_URI + "/{code}",
        name="practice",
        title="One practice",
        description="Why a groovyscope code matters, a bad and a good example, and the fix",
        mime_type="text/markdown",
    )(practice_page)


def practices_index() -> str:
    """The catalogue as Markdown: the review checklist groups, then one row per code."""
    catalog = practices.catalog()
    lines = ["# Groovy practices", "", "## Review checklist", ""]
    lines.extend(f"- **{group['title']}** ({group['id']}): {group['check']}" for group in catalog["groups"])
    lines += ["", "## Codes", "", "| Code | Group | Severity | Title |", "|---|---|---|---|"]
    lines.extend(
        f"| {item['code']} | {item.get('group', '')} | {item.get('severity', '')} | {item.get('title', '')} |"
        for item in catalog["entries"]
    )
    lines += ["", f"Read `{PRACTICES_URI}/<code>` for one code; the full guide is {catalog['docs']}."]
    return "\n".join(lines) + "\n"


def practice_page(code: str) -> str:
    """One code as Markdown: why it matters, the bad and good forms, the fix and the guide section."""
    found = practices.entry(code.strip().upper())
    if found is None:
        return f"# {code}\n\nNot a groovyscope practice code; `explain` covers CodeNarc rules and Groovy checkers.\n"
    parts = [f"# {found['code']}: {found.get('title', '')}", ""]
    group, severity, groovy = (found.get(key, "") for key in ("group", "severity", "groovy"))
    parts.append(f"Group {group}, severity {severity}, Groovy {groovy}.")
    for label, key in (("Why", "why"), ("Instead of", "bad"), ("Write", "good"), ("Fix", "fix")):
        value = found.get(key)
        if value:
            body = f"`{value}`" if key in {"bad", "good"} else str(value)
            parts += ["", f"**{label}:** {body}"]
    link = practices.link(found["code"])
    if link:
        parts += ["", f"Guide: {link}"]
    return "\n".join(parts) + "\n"


def register_prompts(server: MCPServer) -> None:
    """Each shipped skill as an MCP prompt (Kiro lists them under `/`), with one optional `arguments` string."""
    for skill in skills.load_skills():
        server.prompt(name=skill["name"], title=skill["name"].replace("-", " "), description=skill["description"])(
            prompt_for(skill)
        )


def prompt_for(skill: skills.Skill) -> Callable[[str], str]:
    def prompt(arguments: str = "") -> str:
        return skills.render_prompt(skill, arguments)

    prompt.__name__ = skill["name"].replace("-", "_")
    prompt.__doc__ = skill["description"]
    return prompt


NEEDS_REVIEW_NOTE = (
    "these fixes change what the code does (intdiv, a null check, @CompileStatic...): read each finding, then pass "
    "its code in `codes` to apply it, or fix it by hand"
)
LEFT_NOTE = (
    "findings of fixable codes with no safe fix here: the type of a def local could not be inferred (no classpath, or "
    "a dynamic value) or the fix clashed with another; fix them by hand, or run `groovyscope classpath` and fix again"
)


def build_server(root: Path, call: Call, output: Mapping[str, Any], environ: Mapping[str, str]) -> MCPServer:
    """An MCP server exposing the tools of :func:`build_tools` with titles, behaviour annotations and compact
    results."""
    server = MCPServer(SERVER_NAME, instructions=INSTRUCTIONS)
    register_prompts(server)
    register_resources(server)
    structured = bool(output.get("structured", False))
    max_bytes = int(output.get("maxBytes", 16000))
    record = client_recorder(daemon.client_path(root, environ))
    for name, function in build_tools(root, call, output, environ).items():
        title, read_only = TOOL_INFO.get(name, (name, True))
        # The title is on the tool already; the hints are what clients that honour them act on.
        annotations = ToolAnnotations(
            read_only_hint=read_only,
            destructive_hint=False,
            idempotent_hint=True,
            open_world_hint=False,
        )
        tool = respond(
            diagnostics.timed(name, existing_paths(root, function), LOG),
            structured=structured,
            max_bytes=max_bytes,
            on_client=record,
        )
        server.tool(
            name=name,
            title=title,
            description=description(name, function.__doc__),
            annotations=annotations,
            structured_output=False,
        )(tool)
        compact_registered_schema(server, name)
    return server


def client_entry() -> dict[str, Any]:
    """``{"client": ...}`` for the client of the tool call in progress (name, capabilities, whether it can be
    asked), or nothing outside a call."""
    client = interaction.client_summary(interaction.current())
    return {} if client is None else {"client": client}


def client_recorder(target: Path) -> Callable[[Any], None]:
    """Records the MCP client (name, version, protocol, capabilities, whether it can be asked) in ``target`` once
    per server, from the first tool call: `workspace_status`, `groovyscope doctor` and `groovyscope checks report`
    read it to say why no confirmation was asked. A failure to write is logged, never raised."""
    done: list[bool] = []

    def record(context: Any) -> None:
        if done:
            return
        summary = interaction.client_summary(context)
        if summary is None:
            return
        done.append(True)
        LOG.info(
            "MCP client %s %s (protocol %s): elicitation %s",
            summary["name"],
            summary["version"],
            summary["protocol"],
            "yes" if summary["elicitation"] else "no",
        )
        try:
            target.parent.mkdir(parents=True, exist_ok=True)
            write_atomically(target, compact_json({**summary, "recorded": int(time.time())}) + "\n")
        except OSError as exc:
            LOG.warning("could not record the MCP client in %s: %s", target, exc)

    return record


def compact_schema(schema: Mapping[str, Any]) -> dict[str, Any]:
    """The input schema without what costs context and says nothing: the generated titles (the argument names
    say the same), ``anyOf [T, null]`` with ``default: null`` (an optional argument is one that is not
    required), the arguments model's own title. Validation is unchanged: the SDK checks arguments against the
    function signature, not against this schema, and ``null`` is still accepted where it was."""
    properties: dict[str, Any] = {}
    for key, value in (schema.get("properties") or {}).items():
        properties[key] = compact_property(value) if isinstance(value, Mapping) else value
    compact: dict[str, Any] = {"type": schema.get("type", "object"), "properties": properties}
    if schema.get("required"):
        compact["required"] = list(schema["required"])
    for key in ("$defs", "additionalProperties"):
        if key in schema:
            compact[key] = schema[key]
    return compact


def compact_property(prop: Mapping[str, Any]) -> dict[str, Any]:
    """One argument's schema, compacted as :func:`compact_schema` describes."""
    out = {key: value for key, value in prop.items() if key != "title"}
    options = out.get("anyOf")
    if isinstance(options, list) and len(options) == 2 and {"type": "null"} in options:  # noqa: PLR2004 - T or null
        other = next(option for option in options if option != {"type": "null"})
        if isinstance(other, Mapping) and isinstance(other.get("type"), str) and "anyOf" not in other:
            del out["anyOf"]
            out = {**{k: v for k, v in other.items() if k != "title"}, **out, "type": [other["type"], "null"]}
    if "default" in out and out["default"] is None:
        del out["default"]
    return out


def compact_registered_schema(server: MCPServer, name: str) -> None:
    """Replaces the advertised input schema of a registered tool with its compact form. The SDK keeps it on the
    tool record; when a later SDK stores it elsewhere the full schema stays, which is larger but still right."""
    manager = getattr(server, "_tool_manager", None)
    tool = manager.get_tool(name) if manager is not None and hasattr(manager, "get_tool") else None
    if tool is not None and isinstance(getattr(tool, "parameters", None), dict):
        tool.parameters = compact_schema(tool.parameters)


Configuration = Callable[[], tuple[str, str] | None]
RELOAD_METHOD = "groovyscope/reloadConfig"
DEFAULT_REQUEST_TIMEOUT_SECONDS = 100.0  # under Kiro's 120 s tool budget
DEFAULT_MAX_IN_FLIGHT = 4
RELOAD_TIMEOUT_SECONDS = 60.0
Opener = Callable[[Path], Any]
EngineCall = Callable[..., Any]
Dispatch = Callable[[str, dict[str, Any], float | None], Any]


def confirm_edits(policy: str, action: str, changed: Sequence[FileEdit], root: Path) -> str:
    """Asks before ``changed`` are written (see :mod:`groovyscope.interaction`); the decision."""
    files = [
        (relative_path(edit["path"].as_uri(), root), changed_line_count(edit["before"], edit["after"]))
        for edit in changed
    ]
    return interaction.confirm(interaction.write_question(action, files), policy=policy)


def declined(decision: str) -> bool:
    return decision in {interaction.DECLINED, interaction.CANCELLED}


def with_decision(result: dict[str, Any], decision: str) -> dict[str, Any]:
    """The answer with the user's decision when one was asked for; a decline also says that nothing changed, and
    a client that cannot be asked is named as such."""
    if decision == interaction.UNSUPPORTED:
        return result
    if decision == interaction.NOT_OFFERED:
        result["confirmation"] = interaction.NOT_OFFERED
        result["confirmationNote"] = interaction.NOT_OFFERED_NOTE
        return result
    result["confirmation"] = decision
    if declined(decision):
        result["note"] = "the user said no; nothing was written"
    return result


def write_if_unchanged(edit: FileEdit, refused: list[str]) -> bool:
    """Writes the edit unless the file changed on disk since it was read; then it joins ``refused`` with the
    reason and nothing is written. True when written."""
    try:
        write_document(edit["path"], edit["after"], expected=edit["before"])
    except FileChangedError as exc:
        refused.append(f"{edit['path'].as_uri()} ({exc})")
        return False
    return True


def request_limits(settings: Mapping[str, Any] | None) -> tuple[float, int]:
    """``(requestTimeoutSeconds, maxInFlight)`` from the ``mcp`` section, with the defaults filled in."""
    section = settings or {}
    budget = float(section.get("requestTimeoutSeconds", DEFAULT_REQUEST_TIMEOUT_SECONDS))
    in_flight = int(section.get("maxInFlight", DEFAULT_MAX_IN_FLIGHT))
    return (max(1.0, budget), max(1, in_flight))


def flight_key(method: str, params: Mapping[str, Any]) -> str:
    """Two calls with the same method and parameters are the same work: the second joins the first."""
    return method + " " + json.dumps(params, sort_keys=True, default=str, separators=(",", ":"))


def engine_dispatcher(
    socket_file: Path,
    reconnect: Callable[[], Path] | None,
    *,
    opener: Opener,
    engine: EngineCall,
    closer: Callable[[Any], None],
    configuration: Configuration | None,
    budget: float,
) -> tuple[Dispatch, Callable[[], None]]:
    """One engine channel with a configuration that follows the files on disk and a reconnect-and-retry when the
    engine went away (daemon stopped, JVM killed, upgraded). The lock is held only to read or swap the channel and
    to claim a configuration push, never while a request or a reload runs: a reload is sent by one caller while the
    others carry on, and it counts as applied only when the channel it went to is still the current one."""
    state: dict[str, Any] = {"session": opener(socket_file), "applied": None, "sending": None}
    session_lock = threading.Lock()

    def apply_configuration(session: Any, deadline: float) -> None:
        current = configuration() if configuration is not None else None
        if current is None:
            return
        digest, document = current
        with session_lock:
            sending = state["sending"]
            if (
                state["session"] is not session
                or state["applied"] == digest
                or (sending is not None and sending[0] is session and sending[1] == digest)
            ):
                return
            state["sending"] = (session, digest)
        try:
            # Bounded by the call that carries it, so a slow reload never holds a slot past the caller's deadline.
            left = max(0.001, min(RELOAD_TIMEOUT_SECONDS, deadline - time.monotonic()))
            reply = engine(session, RELOAD_METHOD, {"config": document}, timeout=left)
        except RuntimeError as exc:  # the engine refused the document; the old configuration stays in force
            LOG.warning("engine kept its configuration: %s", exc)
        except BaseException:
            with session_lock:
                state["sending"] = None
            raise
        else:
            if isinstance(reply, Mapping) and reply.get("reloaded"):
                LOG.info("engine configuration reloaded (%s)", digest[:12])
        with session_lock:
            if state["session"] is session:  # compare and swap: a reload sent to a replaced channel counts for nothing
                state["applied"] = digest
            state["sending"] = None

    def replace(session: Any, exc: BaseException) -> Any:
        with session_lock:
            if state["session"] is session:  # the first caller to notice replaces it; the others reuse it
                LOG.info("engine session lost (%s); reconnecting", exc)
                closer(session)
                state["session"] = opener(reconnect()) if reconnect is not None else session
                state["applied"] = None
            return state["session"]

    def dispatch(method: str, params: dict[str, Any], timeout: float | None = None) -> Any:
        deadline = time.monotonic() + (budget if timeout is None else timeout)
        with session_lock:
            session = state["session"]
        try:
            apply_configuration(session, deadline)
            return engine(session, method, params, timeout=max(0.001, deadline - time.monotonic()))
        except TimeoutError:
            # TimeoutError is an OSError in Python 3, but a slow engine is alive: never reconnect for it, and never
            # send the request again (the channel already told the engine to stop it).
            raise
        except (OSError, ValueError) as exc:
            # The socket died, the stream is out of frame, a write broke or the daemon is exiting: only a fresh
            # channel can answer.
            if reconnect is None:
                raise
            session = replace(session, exc)
            apply_configuration(session, deadline)
            return engine(session, method, params, timeout=max(0.001, deadline - time.monotonic()))

    return (dispatch, lambda: closer(state["session"]))


DEFAULT_PROGRESS_SECONDS = 5.0


def single_flight(
    dispatch: Dispatch, budget: float, max_in_flight: int, progress_every: float = DEFAULT_PROGRESS_SECONDS
) -> Call:
    """``dispatch`` behind two guardrails: at most ``max_in_flight`` calls reach the engine at a time (the rest
    queue), and a call identical to one still running joins it instead of doubling the work. Every call has one
    deadline, ``budget`` from its arrival, shared by the wait for a slot and the engine request (which is sent with
    the time left, so the engine is told to stop it at the deadline); past it the caller gets a "still running" or
    "no free slot" answer rather than a hang. The requests run on a pool of ``max_in_flight`` threads."""
    pool = ThreadPoolExecutor(max_workers=max_in_flight, thread_name_prefix="groovyscope-call")
    in_flight: dict[str, Future[Any]] = {}
    begun: set[Future[Any]] = set()
    flight_lock = threading.Lock()

    def lead(key: str, method: str, params: dict[str, Any], future: Future[Any], deadline: float) -> None:
        try:
            left = deadline - time.monotonic()
            if left <= 0:
                raise TimeoutError(slot_message(method, budget, max_in_flight))  # queued past its deadline
            with flight_lock:
                begun.add(future)
            future.set_result(dispatch(method, params, left))
        except BaseException as exc:  # every outcome must reach the waiters
            future.set_exception(exc)
        finally:
            with flight_lock:
                begun.discard(future)
                if in_flight.get(key) is future:
                    del in_flight[key]

    def call(method: str, params: dict[str, Any]) -> Any:
        key = flight_key(method, params)
        own: Future[Any] = Future()
        deadline = time.monotonic() + budget
        with flight_lock:
            future = in_flight.setdefault(key, own)
        leader = future is own
        if leader:
            # The request runs on the pool so this thread stays free to tell the client it is still waiting.
            pool.submit(lead, key, method, params, future, deadline)
        started = time.monotonic()
        while True:
            left = deadline - time.monotonic()
            if left <= 0:
                with flight_lock:
                    sent = future in begun
                if leader and not sent:
                    raise RuntimeError(slot_message(method, budget, max_in_flight))
                raise RuntimeError(late_message(method, budget, leader=leader))
            done, _ = futures.wait([future], timeout=min(progress_every, left))
            if done:
                break
            waited = time.monotonic() - started
            interaction.progress(waited, None, f"{method}: the engine is still working ({waited:.0f}s)")
        try:
            return future.result()
        except TimeoutError as exc:
            if str(exc).startswith(f"{method} waited"):
                raise RuntimeError(str(exc)) from exc
            raise RuntimeError(late_message(method, budget, leader=True)) from exc

    return call


def slot_message(method: str, budget: float, max_in_flight: int) -> str:
    return (
        f"{method} waited {budget:.0f}s for a free slot: {max_in_flight} tool calls already run on the engine "
        "(mcp.maxInFlight); retry in a moment"
    )


def late_message(method: str, budget: float, *, leader: bool) -> str:
    who = "the engine was told to stop it" if leader else "another call is still running it"
    return f"{method} did not answer within {budget:.0f}s; {who}. Narrow the paths or retry in a moment"


def session_caller(
    socket_file: Path,
    reconnect: Callable[[], Path] | None,
    *,
    open_session: Opener | None = None,
    configuration: Configuration | None = None,
    limits: Mapping[str, Any] | None = None,
    call_fn: EngineCall | None = None,
    close_fn: Callable[[Any], None] | None = None,
) -> tuple[Call, Callable[[], None]]:
    """A ``call`` bound to one engine channel. Calls run concurrently on the channel (replies are matched by id),
    at most ``mcp.maxInFlight`` at a time, each within ``mcp.requestTimeoutSeconds``; a call over its budget tells
    the engine to stop that request and answers "still running", and an identical call arriving while the first
    one runs joins it. When the engine has gone away the channel is reopened through ``reconnect`` and the
    request retried once. Also returns a closer. ``configuration`` returns the current effective configuration as
    ``(hash, json)``; a hash the engine has not seen yet is sent as ``groovyscope/reloadConfig`` first."""
    budget, max_in_flight = request_limits(limits)
    every = float((limits or {}).get("progressSeconds", DEFAULT_PROGRESS_SECONDS))
    dispatch, close = engine_dispatcher(
        socket_file,
        reconnect,
        opener=rpc.open_channel if open_session is None else open_session,
        engine=rpc.channel_call if call_fn is None else call_fn,
        closer=rpc.close_channel if close_fn is None else close_fn,
        configuration=configuration,
        budget=budget,
    )
    return (single_flight(dispatch, budget, max_in_flight, max(0.5, every)), close)


def serve(
    root: Path,
    socket_file: Path,
    output: Mapping[str, Any],
    environ: Mapping[str, str],
    reconnect: Callable[[], Path] | None = None,
    *,
    configuration: Configuration | None = None,
) -> int:
    """Open one engine session, run the MCP server on stdio until the client disconnects, close the session."""
    # No LSP initialize: every groovyscope/* request names its root, and an initialized session would make
    # the engine lint the whole workspace and push diagnostics for every file to a client that ignores them.
    call, close = session_caller(socket_file, reconnect, configuration=configuration, limits=output.get("mcp"))
    try:
        build_server(root, call, output, environ).run(transport="stdio")
    finally:
        close()
    return 0
