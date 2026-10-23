"""Reuse before writing: suggest_reuse asks the engine's ``groovyscope/similar`` for existing code that already does
what the agent is about to write, and the edit gate and review_changes use the same answer for GS080 to GS082.

GS080: an added block of ``duplicates.minLines`` or more matches existing code (exact or similar shape); blocking in
the gate's ``block`` mode. GS081: a new method with the same name stem and parameter types as one that exists (a
signature match). GS082: a new private helper whose body is one call to another class's method (inline it).
"""

from __future__ import annotations

import difflib
import re
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any

from groovyscope import config as config_module
from groovyscope.paths import Call, relative_path, to_uri

SHAPE_KINDS = frozenset({"exact", "similar"})
# `private Type name(params) { return Other.call(args) }` (one statement, on one line or three).
DELEGATE = re.compile(
    r"\bprivate\s+(?:static\s+)?[\w<>\[\],.? ]+?\s+(?P<name>\w+)\s*\([^)]*\)\s*\{\s*(?:return\s+)?"
    r"(?P<target>[A-Z]\w*(?:\.\w+)*\.\w+)\s*\([^;{}]*\)\s*;?\s*\}",
    re.DOTALL,
)
EngineCall = Callable[[str, dict[str, Any]], Mapping[str, Any]]


def min_lines(settings: Mapping[str, Any]) -> int:
    """``reuse.minLines``, else ``duplicates.minLines``."""
    reuse: Mapping[str, Any] = settings.get("reuse") or {}
    duplicates: Mapping[str, Any] = settings.get("duplicates") or {}
    return int(reuse.get("minLines") or duplicates.get("minLines") or config_module.default("duplicates", "minLines"))


def compact_match(match: Mapping[str, Any], root: Path) -> dict[str, Any]:
    return {
        "symbol": match.get("symbol"),
        "path": relative_path(str(match.get("uri") or ""), root),
        "lines": [match.get("line"), match.get("endLine")],
        "kind": match.get("kind"),
        "score": match.get("score"),
        "reason": match.get("reason"),
    }


def added_blocks(before: str, after: str, minimum: int) -> list[tuple[int, int, str]]:
    """Runs of ``minimum`` or more consecutive added lines in ``after``: ``(first, last, text)``, 1-based."""
    old, new = before.splitlines(), after.splitlines()
    blocks: list[tuple[int, int, str]] = []
    for tag, _i1, _i2, j1, j2 in difflib.SequenceMatcher(None, old, new, autojunk=False).get_opcodes():
        if tag in {"replace", "insert"} and j2 - j1 >= minimum:
            blocks.append((j1 + 1, j2, "\n".join(new[j1:j2])))
    return blocks


def delegating_helpers(text: str) -> list[tuple[str, str]]:
    """``(helper, target)`` for each private method in ``text`` whose body is one call to another class's method."""
    return [(m["name"], m["target"]) for m in DELEGATE.finditer(text)]


def write_findings(
    call: EngineCall, root: Path, path: Path, *, before: str, after: str, settings: Mapping[str, Any]
) -> tuple[list[str], list[str]]:
    """GS080 reasons (blocking) and GS081/GS082 advice for a write that turns ``before`` into ``after``."""
    blocking: list[str] = []
    advice: list[str] = []
    for first, last, block in added_blocks(before, after, min_lines(settings)):
        params: dict[str, Any] = {"root": str(root), "text": block}
        if path.is_file():  # the class the block goes into scopes the search; a new file's package line does
            params.update({"uri": path.as_uri(), "line": first})
        try:
            matches = call("groovyscope/similar", params).get("matches") or []
        except (OSError, RuntimeError, ValueError):
            continue  # the block may not parse on its own; the preview reports what matters
        for match in matches:
            found = compact_match(match, root)
            where = f"{found['symbol']} ({found['path']}:{found['lines'][0]})"
            if match.get("kind") in SHAPE_KINDS:
                blocking.append(
                    f"lines {first}-{last} [GS080] repeat {where}: call it, or extract a shared method with "
                    "extract_method"
                )
                break
            if match.get("kind") == "signature":
                advice.append(f"lines {first}-{last} [GS081] a method like {where} exists: {match.get('reason')}")
    added = "\n".join(block for _first, _last, block in added_blocks(before, after, 1))
    advice += [f"[GS082] {name} only calls {target}: call it directly" for name, target in delegating_helpers(added)]
    return blocking, advice


def reuse_tools(root: Path, call: Call, cap: int, settings: Mapping[str, Any]) -> dict[str, Callable[..., Any]]:
    """suggest_reuse, bound to one workspace and engine session."""

    def suggest_reuse(  # noqa: PLR0917 - the MCP tool's parameters
        text: str | None = None,
        symbol: str | None = None,
        path: str | None = None,
        line: int | None = None,
        paths: list[str] | None = None,
        limit: int | None = None,
    ) -> dict[str, Any]:
        """Existing code that already does what you are about to write: for TEXT (a method or block), or SYMBOL in
        PATH (LINE picks an overload), methods with the same shape, the same name stem and parameter types, or the
        same calls, each with a score and a reason. PATHS narrows the search. Call it before writing a helper."""
        params: dict[str, Any] = {"root": str(root), "limit": min(limit or cap, cap)}
        if text:
            params["text"] = text
            if path and (root / path).is_file():
                params["uri"] = to_uri(root, path)
        elif symbol and path:
            params.update({"uri": to_uri(root, path), "symbol": symbol})
        else:
            return {"error": "give TEXT, or SYMBOL with its PATH"}
        if line is not None:
            params["line"] = line
        if paths:
            params["paths"] = list(paths)
        params["minLines"] = min_lines(settings)
        raw = call("groovyscope/similar", params)
        matches = [compact_match(m, root) for m in raw.get("matches") or []]
        result: dict[str, Any] = {"count": len(matches), "matches": matches}
        if raw.get("symbol"):
            result["symbol"] = raw["symbol"]
        if not matches:
            result["note"] = "nothing similar: write it"
        return result

    return {"suggest_reuse": suggest_reuse}


def duplicates_added(
    root: Path, call: Call, files: Sequence[Mapping[str, Any]], minimum: int, cap: int
) -> list[dict[str, Any]]:
    """For review_changes: added blocks of ``minimum`` lines or more that repeat existing code (exact or similar)."""
    found: list[dict[str, Any]] = []
    for file in files:
        ranges = [[int(a), int(b)] for a, b in file.get("ranges") or [] if int(b) - int(a) + 1 >= minimum]
        if not ranges:
            continue
        params = {"root": str(root), "uri": to_uri(root, str(file["path"])), "ranges": ranges, "minLines": minimum}
        try:
            answer = call("groovyscope/similar", params)
        except (OSError, RuntimeError, ValueError):
            continue
        for entry in answer.get("byRange") or []:
            shapes = [compact_match(m, root) for m in entry.get("matches") or [] if m.get("kind") in SHAPE_KINDS]
            if shapes:
                found.append({"path": file["path"], "lines": entry.get("range"), "repeats": shapes[0]})
    return found[:cap]
