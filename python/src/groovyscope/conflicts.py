"""Merge conflicts in a working tree file: the hunks git left (``<<<<<<<``, an optional ``|||||||`` base,
``=======``, ``>>>>>>>``), each side's lines, the resolution a hunk needs when one side did not change it, and the
text with chosen resolutions applied.

Hand edits of conflict markers go wrong in two ways this module is built against. A marker removed on its own
leaves the rest of its hunk behind (a ``=======`` and a ``>>>>>>>`` with no ``<<<<<<<``): those are reported as
leftovers with their lines, never guessed at. And when both sides added code at the same place, git moves the
lines the two additions share (typically the closing ``}`` of a method) out of the hunk, so joining the two sides
as they stand drops a brace: :func:`both_variants` produces the joins that repeat those shared lines, for the
caller to keep the first one that parses.

Pure apart from :func:`operation`, :func:`unmerged_paths`, :func:`changed_paths`, :func:`show` and
:func:`index_base`, which run git through an injected runner.
"""

from __future__ import annotations

import re
import subprocess
import tempfile
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Literal, NotRequired, TypedDict

MARKER_SIZE = 7
OPEN = "<" * MARKER_SIZE
BASE = "|" * MARKER_SIZE
SPLIT = "=" * MARKER_SIZE
CLOSE = ">" * MARKER_SIZE
GIT_TIMEOUT_SECONDS = 30.0
# A line that is a conflict marker: exactly seven characters, then a space and a label or the end of the line.
# Longer runs (a "=========" underline in a licence file) are content.
MARKER_LINE = re.compile(r"^(<{7}|\|{7}|={7}|>{7})(?: |$)")
# Markers that only git writes; a lone "=======" is also a Markdown heading underline, so it alone proves nothing.
DEFINITE_MARKER = re.compile(r"^(?:<{7}|>{7}|\|{7})(?: |$)", re.MULTILINE)
IMPORT_LINE = re.compile(r"^\s*import\s")

Choice = Literal["ours", "theirs", "both", "theirs-then-ours", "base", "neither"]
CHOICES: tuple[Choice, ...] = ("ours", "theirs", "both", "theirs-then-ours", "base", "neither")
Operation = Literal["merge", "rebase", "cherry-pick", "revert"]
Runner = Callable[..., subprocess.CompletedProcess[str]]


class Hunk(TypedDict):
    number: int  # 1-based, in file order
    start: int  # 0-based index of the <<<<<<< line
    end: int  # 0-based index of the >>>>>>> line
    oursLabel: str
    theirsLabel: str
    ours: list[str]  # lines with their endings
    base: list[str] | None  # only with merge.conflictStyle diff3 or zdiff3, or filled in from the index
    theirs: list[str]


class Leftover(TypedDict):
    line: int  # 1-based
    marker: str  # the marker line as written


class Parsed(TypedDict):
    lines: list[str]
    hunks: list[Hunk]
    leftovers: list[Leftover]
    newline: str


class Suggestion(TypedDict):
    take: Choice
    why: str


class Resolution(TypedDict):
    """What to put in place of one hunk: a choice, or the agent's own text."""

    take: NotRequired[Choice]
    text: NotRequired[str]


def newline_of(text: str) -> str:
    """The file's line ending: CRLF when its first line ends so, else LF."""
    first = text.find("\n")
    return "\r\n" if first > 0 and text[first - 1] == "\r" else "\n"


def marker_of(line: str) -> str | None:
    """The marker a line is, or None for content."""
    found = MARKER_LINE.match(line.rstrip("\r\n"))
    return found.group(1) if found else None


def label_of(line: str) -> str:
    return line.rstrip("\r\n")[MARKER_SIZE:].strip()


def has_markers(text: str) -> bool:
    """Whether the text holds a marker only git writes (``<<<<<<<``, ``|||||||`` or ``>>>>>>>``)."""
    return DEFINITE_MARKER.search(text) is not None


def parse(text: str) -> Parsed:
    """The complete hunks of ``text`` and every marker that belongs to no complete hunk."""
    lines = text.splitlines(keepends=True)
    hunks: list[Hunk] = []
    leftovers: list[Leftover] = []
    index = 0
    while index < len(lines):
        if marker_of(lines[index]) != OPEN:
            index += 1
            continue
        hunk, after = _hunk_at(lines, index, len(hunks) + 1)
        if hunk is None:
            leftovers.append({"line": index + 1, "marker": lines[index].rstrip("\r\n")})
            index += 1
            continue
        hunks.append(hunk)
        index = after
    inside = {i for hunk in hunks for i in range(hunk["start"], hunk["end"] + 1)}
    orphans = _orphans(lines, inside)
    leftovers.extend({"line": i + 1, "marker": lines[i].rstrip("\r\n")} for i in orphans)
    leftovers.sort(key=lambda item: item["line"])
    return {"lines": lines, "hunks": hunks, "leftovers": leftovers, "newline": newline_of(text)}


def _hunk_at(lines: Sequence[str], start: int, number: int) -> tuple[Hunk | None, int]:
    """The hunk that opens at ``start``, or None when it is not closed before the next ``<<<<<<<``."""
    sections: dict[str, list[str]] = {"ours": [], "base": [], "theirs": []}
    section = "ours"
    has_base = False
    for index in range(start + 1, len(lines)):
        marker = marker_of(lines[index])
        if marker == OPEN or (marker == CLOSE and section != "theirs"):
            return (None, index)
        if marker == BASE and section == "ours":
            section, has_base = "base", True
        elif marker == SPLIT and section in {"ours", "base"}:
            section = "theirs"
        elif marker == CLOSE:
            hunk: Hunk = {
                "number": number,
                "start": start,
                "end": index,
                "oursLabel": label_of(lines[start]),
                "theirsLabel": label_of(lines[index]),
                "ours": sections["ours"],
                "base": sections["base"] if has_base else None,
                "theirs": sections["theirs"],
            }
            return (hunk, index + 1)
        else:
            sections[section].append(lines[index])
    return (None, len(lines))


def _orphans(lines: Sequence[str], inside: set[int]) -> list[int]:
    """Marker lines outside every complete hunk: ``>>>>>>>`` and ``|||||||`` always, ``=======`` only when an
    orphan ``>>>>>>>`` follows it before the next ``<<<<<<<`` (else it may be a heading underline)."""
    found: list[int] = []
    pending_split: list[int] = []
    for index, line in enumerate(lines):
        if index in inside:
            continue
        marker = marker_of(line)
        if marker == SPLIT:
            pending_split.append(index)
        elif marker in {CLOSE, BASE}:
            found.extend(pending_split)
            pending_split = []
            found.append(index)
        elif marker == OPEN:
            pending_split = []
    return sorted(set(found))


def _norm(lines: Sequence[str]) -> list[str]:
    return [line.rstrip("\r\n") for line in lines]


def _words(lines: Sequence[str]) -> list[str]:
    return [" ".join(line.split()) for line in lines if line.strip()]


def suggest(hunk: Hunk) -> Suggestion | None:
    """The resolution a hunk needs when it can be told from the lines alone, or None when it needs a reader."""
    ours, theirs = _norm(hunk["ours"]), _norm(hunk["theirs"])
    base = _norm(hunk["base"]) if hunk["base"] is not None else None
    code = [line for line in (*hunk["ours"], *hunk["theirs"]) if line.strip()]
    rules: tuple[tuple[bool, Choice, str], ...] = (
        (ours == theirs, "ours", "both sides made the same change"),
        (base is not None and ours == base, "theirs", "only theirs changed these lines"),
        (base is not None and theirs == base, "ours", "only ours changed these lines"),
        (_words(hunk["ours"]) == _words(hunk["theirs"]), "ours", "the sides differ in whitespace only"),
        (
            bool(code) and all(IMPORT_LINE.match(line) for line in code),
            "both",
            "both sides added imports; both keeps each import once",
        ),
        (
            base is not None and not _words(base) and bool(_words(ours)) and bool(_words(theirs)),
            "both",
            "both sides added different lines here (the base had none): keep both, then read",
        ),
    )
    for applies, take, why in rules:
        if applies:
            return {"take": take, "why": why}
    return None


def side_lines(hunk: Hunk, take: Choice) -> list[str]:
    """The lines a choice puts in place of the hunk, before any boundary repair."""
    if take == "ours":
        return list(hunk["ours"])
    if take == "theirs":
        return list(hunk["theirs"])
    if take == "base":
        if hunk["base"] is None:
            msg = f"hunk {hunk['number']} has no base section; choose ours, theirs or both"
            raise ValueError(msg)
        return list(hunk["base"])
    if take == "neither":
        return []
    first, second = (hunk["ours"], hunk["theirs"]) if take == "both" else (hunk["theirs"], hunk["ours"])
    return [*first, *new_imports(second, first)]


def new_imports(lines: Sequence[str], kept: Sequence[str]) -> list[str]:
    """``lines`` without the imports ``kept`` already has."""
    have = {line.strip() for line in kept if IMPORT_LINE.match(line)}
    return [line for line in lines if not (IMPORT_LINE.match(line) and line.strip() in have)]


def as_lines(text: str, newline: str) -> list[str]:
    """Replacement text as lines in the file's own line ending, the last one ending too (a marker followed it)."""
    if not text:
        return []
    return [line + newline for line in text.splitlines()]


def both_variants(parsed: Parsed, hunk: Hunk, take: Choice, limit: int) -> list[tuple[str, list[str]]]:
    """Ways to keep both sides of ``hunk``, plainest first, each with a note: the plain join, then joins that
    repeat up to ``limit`` lines that follow the hunk (closing what the first side opened) or that precede it
    (opening what the second side continues), which git moved out of the hunk because both sides share them."""
    first, second = (hunk["ours"], hunk["theirs"]) if take == "both" else (hunk["theirs"], hunk["ours"])
    second = new_imports(second, first)
    after = parsed["lines"][hunk["end"] + 1 : hunk["end"] + 1 + limit]
    before = parsed["lines"][max(0, hunk["start"] - limit) : hunk["start"]]
    blank = [parsed["newline"]] if second and second[0].strip() else []
    shapes = [(closing, 0) for closing in range(1, len(after) + 1)]
    shapes += [(0, opening) for opening in range(1, len(before) + 1)]
    shapes += [(c, o) for c in range(1, len(after) + 1) for o in range(1, len(before) + 1)]
    likely = _likely_shape(first, second, after, before)
    if likely in shapes:
        shapes.remove(likely)
        shapes.insert(0, likely)
    variants: list[tuple[str, list[str]]] = []
    for closing, opening in shapes:
        closes, opens = after[:closing], before[len(before) - opening :]
        variants.append((_repeat_note(closes, opens), [*first, *closes, *(blank if closes else []), *opens, *second]))
    plain = ("plain", [*first, *second])
    variants.insert(0 if likely == (0, 0) else 1, plain)
    return variants


def _indent(line: str) -> int:
    return len(line) - len(line.lstrip())


def _likely_shape(
    first: Sequence[str], second: Sequence[str], after: Sequence[str], before: Sequence[str]
) -> tuple[int, int]:
    """The repeat the code points at. By braces: the lines after the hunk that close what the first side opens, and
    the annotations and comments above the hunk that the second side's declaration shares. Failing that, by
    indentation: when the first side ends deeper than the second begins, the closing lines after the hunk down to
    the second side's depth; when the second begins deeper, the opening lines above it. ``(0, 0)``: join plainly."""
    closing = _closing_count(_depth(first), after)
    opening = _decorations(second, before) if closing else 0
    if closing or opening:
        return (closing, opening)
    return _indented_shape(first, second, after, before)


def _depth(lines: Sequence[str]) -> int:
    """Braces opened minus closed, outside quoted strings and line comments (a rough count, for ordering only)."""
    depth = 0
    for line in lines:
        code = re.sub(r"'(?:[^'\\]|\\.)*'|\"(?:[^\"\\]|\\.)*\"", "", line).split("//", 1)[0]
        depth += code.count("{") - code.count("}")
    return depth


def _closing_count(open_braces: int, after: Sequence[str]) -> int:
    """How many lines after the hunk close the ``open_braces`` the first side leaves open; 0 when they do not."""
    if open_braces <= 0:
        return 0
    for count in range(1, len(after) + 1):
        if open_braces + _depth(after[:count]) == 0:
            return count
    return 0


def _decorations(second: Sequence[str], before: Sequence[str]) -> int:
    """Annotations and comments right above the hunk, at the depth of the second side's first line, that its
    declaration shares with the first side's (git keeps one copy above the hunk)."""
    head = next((line for line in second if line.strip()), None)
    if head is None or head.strip().startswith(("@", "//", "/*", "*")):
        return 0
    count = 0
    for line in reversed(before):
        if not line.strip().startswith(("@", "//", "/*", "*")) or _indent(line) != _indent(head):
            break
        count += 1
    return count


def _indented_shape(
    first: Sequence[str], second: Sequence[str], after: Sequence[str], before: Sequence[str]
) -> tuple[int, int]:
    last = next((line for line in reversed(first) if line.strip()), None)
    head = next((line for line in second if line.strip()), None)
    if last is None or head is None:
        return (0, 0)
    end, start = _indent(last), _indent(head)
    if end > start:
        closing = 0
        for line in after:
            if not line.strip() or line.strip()[0] not in ")]}" or _indent(line) < start:
                break
            closing += 1
        return (closing, 0)
    if start > end:
        # The nearest lines above the second side's depth that sit at one depth: a signature with its annotations.
        opening, depth = 0, None
        for line in reversed(before):
            if not line.strip() or _indent(line) >= start or depth not in {None, _indent(line)}:
                break
            depth = _indent(line)
            opening += 1
        return (0, opening)
    return (0, 0)


def _repeat_note(closing: Sequence[str], opening: Sequence[str]) -> str:
    parts = []
    if closing:
        parts.append(f"repeats {len(closing)} line(s) after the hunk ({_show(closing)}) to close the first side")
    if opening:
        parts.append(f"repeats {len(opening)} line(s) before the hunk ({_show(opening)}) to open the second side")
    return " + ".join(parts)


def _show(lines: Sequence[str]) -> str:
    return " / ".join(line.strip() for line in lines if line.strip()) or "blank"


def render(parsed: Parsed, replacements: Mapping[int, Sequence[str]]) -> str:
    """The file with each hunk numbered in ``replacements`` replaced by its lines; every other hunk keeps its
    markers."""
    out: list[str] = []
    cursor = 0
    for hunk in parsed["hunks"]:
        if hunk["number"] not in replacements:
            continue
        out.extend(parsed["lines"][cursor : hunk["start"]])
        out.extend(replacements[hunk["number"]])
        cursor = hunk["end"] + 1
    out.extend(parsed["lines"][cursor:])
    return "".join(out)


def numbered(lines: Sequence[str], first: int, limit: int) -> list[str]:
    """``lines`` as ``NNNN| text`` from line ``first``, the middle elided past ``limit`` lines."""
    rows = [f"{first + i}| {line.rstrip(chr(13) + chr(10))}" for i, line in enumerate(lines)]
    if len(rows) <= limit:
        return rows
    head = limit // 2
    tail = limit - head
    return [*rows[:head], f"... {len(rows) - limit} line(s) not shown ...", *rows[len(rows) - tail :]]


# git: in-progress operation, unmerged and changed files, file versions.


def _git(run: Runner, root: Path, *args: str) -> subprocess.CompletedProcess[str] | None:
    try:
        return run(["git", *args], cwd=root, timeout=GIT_TIMEOUT_SECONDS)
    except (OSError, subprocess.SubprocessError):
        return None


def _git_path(run: Runner, root: Path, name: str) -> Path | None:
    result = _git(run, root, "rev-parse", "--git-path", name)
    if result is None or result.returncode != 0:
        return None
    path = Path(result.stdout.strip())
    return path if path.is_absolute() else root / path


class OperationState(TypedDict):
    operation: Operation
    theirs: str  # the ref whose changes are coming in: MERGE_HEAD, CHERRY_PICK_HEAD, REBASE_HEAD or REVERT_HEAD
    message: str  # the first line of the prepared commit message, naming the branch when git wrote one


OPERATION_HEADS: tuple[tuple[Operation, str], ...] = (
    ("merge", "MERGE_HEAD"),
    ("cherry-pick", "CHERRY_PICK_HEAD"),
    ("revert", "REVERT_HEAD"),
    ("rebase", "REBASE_HEAD"),
)


def operation(root: Path, run: Runner) -> OperationState | None:
    """The merge, rebase, cherry-pick or revert in progress in the repository at ``root``, or None."""
    for name, head in OPERATION_HEADS:
        path = _git_path(run, root, head)
        if path is not None and path.is_file():
            message_path = _git_path(run, root, "MERGE_MSG")
            message = ""
            if message_path is not None and message_path.is_file():
                message = message_path.read_text(encoding="utf-8", errors="replace").partition("\n")[0].strip()
            return {"operation": name, "theirs": head, "message": message}
    for folder in ("rebase-merge", "rebase-apply"):
        path = _git_path(run, root, folder)
        if path is not None and path.is_dir():
            return {"operation": "rebase", "theirs": "REBASE_HEAD", "message": ""}
    return None


def busy(root: Path, run: Runner) -> bool:
    """Whether git is in the middle of a merge, rebase, cherry-pick or revert at ``root``."""
    return operation(root, run) is not None


def _names(result: subprocess.CompletedProcess[str] | None) -> list[str]:
    if result is None or result.returncode != 0:
        return []
    return [name for name in result.stdout.split("\0") if name]


def unmerged_paths(root: Path, run: Runner) -> list[str]:
    """Paths git still lists as unmerged, relative to ``root``."""
    return sorted(set(_names(_git(run, root, "diff", "--name-only", "--diff-filter=U", "-z", "--relative"))))


def changed_paths(root: Path, run: Runner) -> list[str]:
    """Paths changed against HEAD, staged or not (what the commit in progress would carry), relative to ``root``."""
    staged = _names(_git(run, root, "diff", "--cached", "--name-only", "-z", "--relative"))
    unstaged = _names(_git(run, root, "diff", "--name-only", "-z", "--relative"))
    return sorted(set(staged) | set(unstaged))


def show(root: Path, run: Runner, spec: str) -> str | None:
    """``git show SPEC`` (``HEAD:./path``, ``:1:./path``), or None when git has no such object."""
    result = _git(run, root, "show", spec)
    return result.stdout if result is not None and result.returncode == 0 else None


def rev(root: Path, run: Runner, *args: str) -> str | None:
    result = _git(run, root, "rev-parse", "--verify", "--quiet", *args)
    return result.stdout.strip() if result is not None and result.returncode == 0 and result.stdout.strip() else None


def merge_base(root: Path, run: Runner, ours: str, theirs: str) -> str | None:
    result = _git(run, root, "merge-base", ours, theirs)
    return result.stdout.strip() if result is not None and result.returncode == 0 and result.stdout.strip() else None


def index_base(root: Path, run: Runner, relative: str, hunks: Sequence[Hunk]) -> list[Hunk]:
    """``hunks`` with their base sections filled in from git's index, for a file still unmerged there: the three
    staged versions are merged again with ``git merge-file --zdiff3`` (``--diff3`` on an older git) in a temporary
    folder, and a regenerated hunk lends its base to the working hunk with the same two sides. Hunks already
    carrying a base, or with no match, are returned as they are; the working file is never touched."""
    if all(hunk["base"] is not None for hunk in hunks):
        return list(hunks)
    versions = [show(root, run, f":{stage}:./{relative}") for stage in (2, 1, 3)]
    if any(version is None for version in versions):
        return list(hunks)
    regenerated: dict[tuple[tuple[str, ...], tuple[str, ...]], list[str]] = {}
    with tempfile.TemporaryDirectory(prefix="groovyscope-merge-") as folder:
        files = []
        for name, version in zip(("ours", "base", "theirs"), versions, strict=True):
            path = Path(folder) / name
            path.write_text(version or "", encoding="utf-8")
            files.append(str(path))
        for style in ("--zdiff3", "--diff3"):
            result = _git(run, root, "merge-file", "-p", style, "-L", "ours", "-L", "base", "-L", "theirs", *files)
            if result is not None and result.returncode >= 0 and has_markers(result.stdout or ""):
                for hunk in parse(result.stdout)["hunks"]:
                    if hunk["base"] is not None:
                        regenerated[(tuple(_norm(hunk["ours"])), tuple(_norm(hunk["theirs"])))] = hunk["base"]
                break
    filled: list[Hunk] = []
    for hunk in hunks:
        found = regenerated.get((tuple(_norm(hunk["ours"])), tuple(_norm(hunk["theirs"]))))
        filled.append({**hunk, "base": found} if hunk["base"] is None and found is not None else hunk)
    return filled
