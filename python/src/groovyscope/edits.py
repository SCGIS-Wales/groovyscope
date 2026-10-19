"""Apply LSP-style text edits (zero-based line, UTF-16 character ranges) to documents, and write them back only
inside the workspace, atomically, keeping the file's line endings."""

from __future__ import annotations

import difflib
import urllib.parse
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any, TypedDict

from groovyscope import atomic

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
    """Lines a real diff touches: each inserted, deleted or replaced block counts its longer side. An inserted
    line counts once instead of shifting (and counting) every line after it."""
    matcher = difflib.SequenceMatcher(None, before.split("\n"), after.split("\n"), autojunk=False)
    return sum(
        max(old_end - old_start, new_end - new_start)
        for tag, old_start, old_end, new_start, new_end in matcher.get_opcodes()
        if tag != "equal"
    )


def counts_by_path(raw: Mapping[str, Any]) -> dict[Path, dict[str, int]]:
    """An engine map of document URI to {code: count}, keyed by path instead."""
    return {uri_path(uri): {str(k): int(v) for k, v in counts.items()} for uri, counts in raw.items()}


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


class FileChangedError(OSError):
    """The file is not what the edit was computed from: somebody saved it in between."""


def write_document(path: Path, text: str, *, expected: str | None = None) -> None:
    """Replace the file atomically (temporary file, then rename), keeping its mode and line endings. With
    ``expected`` (the text the edit was computed from), a file that reads differently now is left alone and
    :class:`FileChangedError` is raised: an edit never overwrites a save it did not see, also one that lands while
    the new text is being written (the file's stamp is checked again just before the rename). A symbolic link is
    followed: the file it points to is replaced, and the link stays a link."""
    target = path.resolve()
    stamp = file_stamp(target)
    if expected is not None and read_document(target) != expected:
        msg = "changed on disk since it was read; not written"
        raise FileChangedError(msg)

    def unchanged() -> None:
        if expected is not None and file_stamp(target) != stamp:
            msg = "changed on disk while the edit was being written; not written"
            raise FileChangedError(msg)

    atomic.write_text(target, text, guard=unchanged)


def file_stamp(path: Path) -> tuple[int, int] | None:
    """``(mtime_ns, size)``, or None for a file that is not there."""
    try:
        info = path.stat()
    except FileNotFoundError:
        return None
    return (info.st_mtime_ns, info.st_size)


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
