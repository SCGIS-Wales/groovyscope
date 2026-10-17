"""MCP tools that let an agent find and read Groovy code without shell, grep or guessed line ranges:
read_symbol, find_code, find_duplicates and workspace_overview."""

from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any

from groovyscope.mcp_server_support import Call, Paged, clip, relative_path, to_uri

FIND_CODE_KINDS = (
    "catch",
    "throw",
    "call",
    "new",
    "return-null",
    "string-literal",
    "annotation",
    "def",
    "empty-block",
    "field",
    "method",
)


def compact_match(item: Mapping[str, Any], root: Path) -> dict[str, Any]:
    entry: dict[str, Any] = {
        "path": relative_path(str(item.get("uri", "")), root),
        "line": int(item.get("line", 0)) + 1,
        "endLine": int(item.get("endLine", item.get("line", 0))) + 1,
        "detail": item.get("detail"),
        "snippet": clip(item.get("snippet"), 160),
    }
    if item.get("enclosing"):
        entry["in"] = item["enclosing"]
    return entry


def search_tools(
    root: Path, call: Call, paged: Paged, cap: int, *, settings: Mapping[str, Any], max_chars: int
) -> dict[str, Callable[..., Any]]:
    """The tools, bound to one workspace and engine session."""

    def uris(paths: Sequence[str] | None) -> list[str]:
        return [to_uri(root, p) for p in paths or []]

    def read_symbol(path: str, symbol: str, line: int | None = None, max_chars_: int | None = None) -> dict[str, Any]:
        """The source of one class, method or field in PATH (SYMBOL: `name`, `Class.name` or a class name), with
        line numbers. LINE (1-based) picks between overloads. Prefer this over reading line ranges."""
        params: dict[str, Any] = {
            "root": str(root),
            "uri": to_uri(root, path),
            "symbol": symbol,
            "maxChars": min(max_chars_ or max_chars, max_chars),
        }
        if line is not None:
            params["line"] = line - 1
        raw = call("groovyscope/readSymbol", params)
        if not raw or not raw.get("found"):
            candidates = [
                {
                    "name": c.get("name"),
                    "kind": c.get("kind"),
                    "in": c.get("container"),
                    "line": int(c.get("line", 0)) + 1,
                }
                for c in (raw or {}).get("candidates", [])
            ]
            return {"found": False, "candidates": candidates[:cap]}
        start = int(raw.get("startLine", 0)) + 1
        numbered = "\n".join(f"{start + i}: {text}" for i, text in enumerate(str(raw.get("text", "")).split("\n")))
        return {
            "found": True,
            "name": raw.get("name"),
            "kind": raw.get("kind"),
            "in": raw.get("container"),
            "lines": [start, int(raw.get("endLine", 0)) + 1],
            "source": numbered,
            "truncated": bool(raw.get("truncated")),
        }

    def find_code(
        kind: str,
        *,
        paths: list[str] | None = None,
        name: str | None = None,
        type: str | None = None,  # noqa: A002 - the filter is named after what it matches
        pattern: str | None = None,
        modifiers: list[str] | None = None,
        empty_body: bool | None = None,
        rethrows: bool | None = None,
        min_lines: int | None = None,
        min_parameters: int | None = None,
        limit: int | None = None,
        cursor: int = 0,
    ) -> dict[str, Any]:
        """Structural search over the Groovy trees (use instead of grep). KIND: catch (TYPE, EMPTY_BODY, RETHROWS),
        throw (TYPE), call (NAME, TYPE of receiver), new (TYPE), return-null, string-literal (PATTERN regex),
        annotation (NAME), def, empty-block, field (MODIFIERS), method (MODIFIERS, MIN_LINES, MIN_PARAMETERS)."""
        if kind not in FIND_CODE_KINDS:
            return {"error": f"unknown kind {kind!r}", "kinds": list(FIND_CODE_KINDS)}
        params: dict[str, Any] = {"root": str(root), "uris": uris(paths), "kind": kind}
        optional = {
            "name": name,
            "type": type,
            "pattern": pattern,
            "modifiers": modifiers,
            "emptyBody": empty_body,
            "rethrows": rethrows,
            "minLines": min_lines,
            "minParameters": min_parameters,
        }
        params.update({key: value for key, value in optional.items() if value is not None})
        raw = call("groovyscope/findCode", params)
        result = paged([compact_match(item, root) for item in raw.get("matches", [])], limit, cursor)
        result["count"] = raw.get("count", 0)
        return result

    def find_duplicates(
        paths: list[str] | None = None, min_lines: int | None = None, limit: int | None = None, cursor: int = 0
    ) -> dict[str, Any]:
        """Repeated code: groups of blocks with the same structure (identifiers and literals ignored), largest
        first, each with its locations. EXACT groups are copies; SIMILAR groups differ only in names or values."""
        duplicates = settings.get("duplicates", {})
        params = {
            "root": str(root),
            "uris": uris(paths),
            "minLines": int(min_lines or duplicates.get("minLines", 6)),
            "minStatements": int(duplicates.get("minStatements", 3)),
        }
        raw = call("groovyscope/duplicates", params)
        groups = [
            {
                "lines": group.get("lines"),
                "similarity": group.get("similarity"),
                "at": [
                    {
                        "path": relative_path(str(o.get("uri", "")), root),
                        "lines": [int(o.get("line", 0)) + 1, int(o.get("endLine", 0)) + 1],
                        **({"in": o["enclosing"]} if o.get("enclosing") else {}),
                    }
                    for o in group.get("occurrences", [])
                ],
            }
            for group in raw.get("groups", [])
        ]
        result = paged(groups, limit, cursor)
        result["count"] = raw.get("count", len(groups))
        return result

    def workspace_overview() -> dict[str, Any]:
        """A map of the codebase: totals, packages, the largest files and classes with line counts, test files
        and classes without a test. Call this first in an unfamiliar workspace."""
        raw = call("groovyscope/overview", {"root": str(root)})

        def files(items: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
            return [
                {**{k: v for k, v in item.items() if k != "uri"}, "path": relative_path(str(item.get("uri", "")), root)}
                for item in items[:cap]
            ]

        return {
            "files": raw.get("files", 0),
            "lines": raw.get("lines", 0),
            "packages": list(raw.get("packages", []))[:cap],
            "largestFiles": files(raw.get("largestFiles", [])),
            "largestClasses": files(raw.get("largestClasses", [])),
            "testFiles": raw.get("testFiles", 0),
            "untested": list(raw.get("untested", []))[:cap],
        }

    return {
        "read_symbol": read_symbol,
        "find_code": find_code,
        "find_duplicates": find_duplicates,
        "workspace_overview": workspace_overview,
    }
