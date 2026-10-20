"""Compact output shaping for agents: relative paths, pagination with continuation cursors, byte caps."""

from __future__ import annotations

import json
from collections.abc import Sequence
from pathlib import Path
from typing import Any

# LSP diagnostic severities by number, as every front end names them.
SEVERITY_NAMES = {1: "error", 2: "warning", 3: "info", 4: "hint"}


def relativize(path: Path, root: Path) -> str:
    """Path relative to ``root`` with forward slashes; absolute when it lies outside the root."""
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return path.resolve().as_posix()


def compact_json(value: Any, *, sort_keys: bool = True) -> str:
    """JSON without indentation or spaces after separators: pretty printing costs about a third more characters,
    and every character reaches an agent's context. ``sort_keys=False`` keeps the order a result was built in
    (the MCP tools put the summary first); anything JSON cannot encode is written as its text."""
    return json.dumps(value, separators=(",", ":"), sort_keys=sort_keys, ensure_ascii=False, default=str)


def encoded_size(value: Any) -> int:
    return len(compact_json(value).encode("utf-8"))


def page(items: Sequence[Any], *, max_results: int, max_bytes: int, cursor: int = 0) -> dict[str, Any]:
    """Slice ``items`` from ``cursor`` so that at most ``max_results`` fit in ``max_bytes`` of JSON.

    At least one item is always returned when any remain, so a consumer always makes progress.
    """
    start = max(0, min(cursor, len(items)))
    selected: list[Any] = []
    for item in items[start:]:
        if len(selected) >= max_results:
            break
        candidate = [*selected, item]
        if selected and encoded_size(candidate) > max_bytes:
            break
        selected = candidate
    next_cursor = start + len(selected)
    truncated = next_cursor < len(items)
    return {
        "items": selected,
        "total": len(items),
        "truncated": truncated,
        "next_cursor": next_cursor if truncated else None,
    }


def table(rows: Sequence[Sequence[str]]) -> str:
    """Left-aligned columns separated by two spaces; empty input renders as an empty string."""
    if not rows:
        return ""
    widths = [max(len(str(row[i])) for row in rows) for i in range(len(rows[0]))]
    lines = ["  ".join(str(cell).ljust(widths[i]) for i, cell in enumerate(row)).rstrip() for row in rows]
    return "\n".join(lines)
