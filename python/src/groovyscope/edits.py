"""Apply LSP-style text edits (zero-based line/character ranges) to a document."""

from __future__ import annotations

from collections.abc import Sequence
from typing import Any


def line_starts(text: str) -> list[int]:
    starts = [0]
    for index, char in enumerate(text):
        if char == "\n":
            starts.append(index + 1)
    return starts


def offset_of(text: str, starts: Sequence[int], line: int, character: int) -> int:
    """Clamp a position into the document and return its character offset."""
    line = max(0, min(line, len(starts) - 1))
    line_end = starts[line + 1] - 1 if line + 1 < len(starts) else len(text)
    return min(starts[line] + max(0, character), line_end)


def apply_edits(text: str, edits: Sequence[dict[str, Any]]) -> str:
    """Apply edits shaped like LSP ``TextEdit`` JSON, from the end of the document backwards."""
    starts = line_starts(text)
    located: list[tuple[int, int, str]] = []
    for edit in edits:
        start = edit["range"]["start"]
        end = edit["range"]["end"]
        located.append(
            (
                offset_of(text, starts, int(start["line"]), int(start["character"])),
                offset_of(text, starts, int(end["line"]), int(end["character"])),
                str(edit.get("newText", "")),
            )
        )
    located.sort(key=lambda item: (item[0], item[1]), reverse=True)
    result = text
    for start, end, new_text in located:
        result = result[:start] + new_text + result[max(start, end) :]
    return result


def changed_line_count(before: str, after: str) -> int:
    """Lines that differ between two texts (a cheap summary for ``format`` without ``--apply``)."""
    old = before.split("\n")
    new = after.split("\n")
    longest = max(len(old), len(new))
    old += [""] * (longest - len(old))
    new += [""] * (longest - len(new))
    return sum(1 for a, b in zip(old, new, strict=True) if a != b)
