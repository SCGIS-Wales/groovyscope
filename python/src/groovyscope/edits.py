"""Apply LSP-style text edits (zero-based line, UTF-16 character ranges) to documents, and write them back only
inside the workspace, atomically, keeping the file's line endings."""

from __future__ import annotations

import os
import shutil
import tempfile
import urllib.parse
from collections.abc import Sequence
from pathlib import Path
from typing import Any, TypedDict

# Files the engine may hand back edits for; anything else is refused.
BMP_MAX = 0xFFFF  # characters above this take two UTF-16 code units
EDITABLE_SUFFIXES = frozenset({".groovy", ".gvy", ".gy", ".gsh", ".gradle"})


class FileEdit(TypedDict):
    """A document an edit applies to, before and after."""

    path: Path
    before: str
    after: str


def utf16_index(line_text: str, character: int) -> int:
    """The Python string index of an LSP character offset (UTF-16 code units) within one line."""
    units = 0
    for index, char in enumerate(line_text):
        if units >= character:
            return index
        units += 2 if ord(char) > BMP_MAX else 1
    return len(line_text)


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
    return min(starts[line] + utf16_index(text[starts[line] : line_end], max(0, character)), line_end)


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


def uri_path(uri: str) -> Path:
    return Path(urllib.parse.unquote(urllib.parse.urlparse(uri).path))


def is_editable(path: Path, root: Path) -> bool:
    """Only Groovy sources inside the workspace (after resolving symlinks) may be written."""
    try:
        resolved = path.resolve(strict=True)
        inside = resolved.is_relative_to(root.resolve())
    except OSError:
        return False
    named = resolved.suffix.lower() in EDITABLE_SUFFIXES or resolved.name.startswith("Jenkinsfile")
    return inside and named and resolved.is_file()


def read_document(path: Path) -> str:
    """The file's text with its line endings untouched."""
    with path.open(encoding="utf-8", newline="") as handle:
        return handle.read()


def write_document(path: Path, text: str) -> None:
    """Replace the file atomically (temporary file, then rename), keeping its mode and line endings."""
    with tempfile.NamedTemporaryFile(
        "w",
        encoding="utf-8",
        newline="",
        dir=path.parent,
        prefix=f".{path.name}.",
        suffix=".groovyscope-tmp",
        delete=False,
    ) as handle:
        handle.write(text)
        temporary = Path(handle.name)
    try:
        shutil.copymode(path, temporary)
        os.replace(temporary, path)  # noqa: PTH105 - os.replace keeps the intent (atomic rename) explicit
    finally:
        temporary.unlink(missing_ok=True)


def file_edits(edits_by_uri: dict[str, Any], root: Path) -> tuple[list[FileEdit], list[str]]:
    """The documents an engine reply would change, and the URIs refused because they are not Groovy sources
    inside the workspace."""
    changed: list[FileEdit] = []
    refused: list[str] = []
    for uri, edits in sorted(edits_by_uri.items()):
        path = uri_path(uri)
        if not is_editable(path, root):
            refused.append(uri)
            continue
        before = read_document(path)
        changed.append({"path": path, "before": before, "after": apply_edits(before, edits)})
    return (changed, refused)
