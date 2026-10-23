"""Apply LSP-style text edits (zero-based line, UTF-16 character ranges) to documents, and write them back only
inside the workspace, atomically, keeping the file's line endings."""

from __future__ import annotations

import difflib
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any, TypedDict

from groovyscope import atomic
from groovyscope.paths import GROOVY_SUFFIXES, Call, relative_path, uri_to_path

# Files the engine may hand back edits for; anything else is refused.
BMP_MAX = 0xFFFF  # characters above this take two UTF-16 code units
EDITABLE_SUFFIXES = frozenset(GROOVY_SUFFIXES)


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


uri_path = uri_to_path


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


def write_if_unchanged(edit: FileEdit, refused: list[str]) -> bool:
    """Writes the edit unless the file changed on disk since it was read, in which case the file joins ``refused``
    with the reason and nothing is written. True when written."""
    try:
        write_document(edit["path"], edit["after"], expected=edit["before"])
    except FileChangedError as exc:
        refused.append(f"{edit['path'].as_uri()} ({exc})")
        return False
    return True


REFUSED_REASON = "not a Groovy source inside the workspace"


class SettledEdit(TypedDict):
    """One file of a fix or format answer: the edit, the lines it changes and whether it was written."""

    edit: FileEdit
    lines: int
    applied: bool


def settle(changed: Sequence[FileEdit], refused: list[str], *, apply: bool) -> list[SettledEdit]:
    """The edits that stand. With ``apply`` each is written unless its file changed on disk since it was read (that
    file joins ``refused`` and drops out); without, they are listed."""
    settled: list[SettledEdit] = []
    for edit in changed:
        if apply and not write_if_unchanged(edit, refused):
            continue
        settled.append({"edit": edit, "lines": changed_line_count(edit["before"], edit["after"]), "applied": apply})
    return settled


class FixOutcome(TypedDict):
    """What an engine fix reply means per file: the codes applied and how often, what was left without a safe fix,
    what needs review (the fix changes behaviour) and what was rejected, with why."""

    codes: dict[Path, list[str]]
    fixed: dict[Path, dict[str, int]]
    left: dict[Path, dict[str, int]]
    needsReview: dict[Path, dict[str, int]]
    rejected: dict[Path, str]


def fix_outcome(raw: Mapping[str, Any], refused: Sequence[str]) -> FixOutcome:
    """The per-file reading of an engine ``groovyscope/fix`` reply; ``refused`` are the URIs ``file_edits`` turned
    down or a write gave up on."""
    rejected = {uri_path(uri): str(why) for uri, why in raw.get("rejected", {}).items()}
    rejected.update({uri_path(uri.split(" (", 1)[0]): REFUSED_REASON for uri in refused})
    return {
        "codes": {uri_path(uri): list(codes) for uri, codes in raw.get("applied", {}).items()},
        "fixed": counts_by_path(raw.get("appliedCounts", {})),
        "left": counts_by_path(raw.get("left", {})),
        "needsReview": counts_by_path(raw.get("needsReview", {})),
        "rejected": rejected,
    }


def apply_verified(
    root: Path, call: Call, changed: list[FileEdit], *, flag: str = "renamed", what: str = "the rename"
) -> dict[str, Any]:
    """Write the edits, recompile the changed files, and restore every file when a write fails, the engine
    cannot verify, or the edit left new compile errors: the workspace is either fully changed or untouched.
    ``flag`` is the result's key that says whether the edit landed."""
    uris = [edit["path"].as_uri() for edit in changed]
    try:
        errors_before = compile_errors(root, call, uris)
    except (OSError, RuntimeError) as exc:
        return {flag: False, "reason": f"nothing written: the engine could not compile first ({exc})"}
    written: list[FileEdit] = []
    try:
        for edit in changed:
            write_document(edit["path"], edit["after"], expected=edit["before"])
            written.append(edit)
        errors_after = compile_errors(root, call, uris)
    except (OSError, RuntimeError) as exc:
        failed = restore(root, written)
        reason = f"{exc}; the files already changed were restored"
        if failed:
            reason += f", except {', '.join(failed)}: restore them from version control"
        return {flag: False, "rolledBack": True, "reason": reason}
    if errors_after > errors_before:
        failed = restore(root, written)
        reason = (
            f"{what} left {errors_after - errors_before} new compile error(s); the files were restored. "
            "Check the symbol (a same-named member in another class?) or edit by hand"
        )
        if failed:
            reason += f". Restore {', '.join(failed)} from version control"
        return {flag: False, "rolledBack": True, "reason": reason}
    return {"verified": "the changed files compile with no new errors"}


def restore(root: Path, written: list[FileEdit]) -> list[str]:
    """Put the original text back; the paths that could not be restored."""
    failed: list[str] = []
    for edit in written:
        try:
            write_document(edit["path"], edit["before"], expected=edit["after"])
        except OSError:
            failed.append(relative_path(edit["path"].as_uri(), root))
    return failed


def compile_errors(root: Path, call: Call, uris: list[str]) -> int:
    """Compile errors in the given documents, as the engine reports them after re-reading the files."""
    raw = call("groovyscope/check", {"root": str(root), "uris": uris, "include": ["compile"]})
    return sum(
        1 for items in raw.get("diagnostics", {}).values() for item in items if int(item.get("severity", 3)) == 1
    )
