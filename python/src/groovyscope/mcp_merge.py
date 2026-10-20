"""MCP tools for a merge, rebase or cherry-pick that stopped on conflicts: merge_conflicts (where they are and what
each hunk holds), resolve_conflicts (apply a choice per hunk in one write, checked by the engine's parser) and
merge_review (declarations a merge lost, or changes it dropped, in the Groovy files both sides touched).

An agent resolving conflicts by hand edits one marker at a time, misreads where a hunk ends, checks its work by
counting braces and greps the whole tree for markers. These tools answer from git and the parser instead; they
never stage, commit or run the build.
"""

from __future__ import annotations

import inspect
import subprocess
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any, TypedDict, cast

from groovyscope import conflicts, edits, interaction, process
from groovyscope.hooks import is_groovy_source
from groovyscope.paths import Call

PARSE_BATCH = 16  # the engine's limit per groovyscope/parse request
MAX_ERRORS = 5
MAX_SCANNED = 500  # changed files scanned for leftover markers
NO_BASE_TIP = (
    "no base section: run `git config merge.conflictStyle zdiff3` before the next merge so each hunk shows the "
    "original lines and one sided hunks resolve themselves (`git checkout --conflict=zdiff3 -- PATH` rewrites this "
    "file's markers that way now, discarding any edit made to it since the merge stopped)"
)
LEFTOVER_ADVICE = (
    "markers left from a hand edit of a hunk (its other markers were removed): finish these lines by hand, or "
    "restore the file's hunks with `git checkout --conflict=merge -- PATH` (discards edits made to the file since "
    "the merge stopped) and resolve them with resolve_conflicts"
)
NEXT_STEPS = (
    "merge_conflicts PATH shows a file's hunks, with a suggestion where one side left the lines unchanged; "
    "resolve_conflicts PATH applies a choice per hunk in one write and checks that the file still parses; "
    "merge_review then lists declarations the merge lost or changes it dropped. Stage with `git add` yourself."
)


class HunkResolution(TypedDict, total=False):
    """A hunk number with a take or a text."""

    hunk: int
    take: conflicts.Choice
    text: str


class MergeSettings(TypedDict):
    contextLines: int
    sideLines: int
    boundaryLines: int
    reviewFiles: int
    maxFileBytes: int


def merge_settings(settings: Mapping[str, Any]) -> MergeSettings:
    """``merge`` from the tool settings with the defaults filled in."""
    from groovyscope import config  # noqa: PLC0415 - config imports the package lazily too

    section: Mapping[str, Any] = settings.get("merge") or {}

    def value(key: str) -> int:
        found = section.get(key)
        return int(found if found is not None else config.default("merge", key))

    max_bytes = section.get("maxFileBytes")
    return {
        "contextLines": value("contextLines"),
        "sideLines": value("sideLines"),
        "boundaryLines": value("boundaryLines"),
        "reviewFiles": value("reviewFiles"),
        "maxFileBytes": int(max_bytes if max_bytes is not None else config.default("workspace", "maxFileBytes")),
    }


def workspace_file(root: Path, path: str) -> Path:
    """``path`` as a file inside the workspace (symbolic links resolved); ValueError for anything else."""
    target = (root / path).resolve()
    if not target.is_relative_to(root.resolve()):
        msg = f"{path} is outside the workspace"
        raise ValueError(msg)
    if not target.is_file():
        msg = f"{path} is not a file in the workspace"
        raise ValueError(msg)
    return target


def read_text(path: Path, max_bytes: int) -> str | None:
    """The file's text with its line endings, or None when it is larger than ``max_bytes`` (0: no limit), binary
    or not UTF-8."""
    try:
        if max_bytes and path.stat().st_size > max_bytes:
            return None
        data = path.read_bytes()
    except OSError:
        return None
    if b"\0" in data:
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return None


def parse_texts(call: Call, root: Path, name: str, texts: Sequence[str], *, declarations: bool) -> list[dict[str, Any]]:
    """The engine's syntax check (and declarations) of each text, in order. Raises what the call raises."""
    results: list[dict[str, Any]] = []
    for start in range(0, len(texts), PARSE_BATCH):
        params = {"root": str(root), "name": name, "texts": list(texts[start : start + PARSE_BATCH])}
        raw = call("groovyscope/parse", {**params, "declarations": declarations})
        results.extend(cast("list[dict[str, Any]]", raw.get("results", [])))
    return results


def _errors(result: Mapping[str, Any]) -> list[str]:
    return [f"{e.get('line')}:{e.get('column')} {e.get('message')}" for e in (result.get("errors") or [])[:MAX_ERRORS]]


# merge_conflicts


def status(root: Path, run: conflicts.Runner, limits: MergeSettings, cap: int) -> dict[str, Any]:
    """The operation in progress, the files git lists as unmerged with their hunks, and changed files (staged
    ones included) that still hold markers."""
    state = conflicts.operation(root, run)
    unmerged = conflicts.unmerged_paths(root, run)
    files: list[dict[str, Any]] = []
    for relative in unmerged[:cap]:
        text = read_text(root / relative, limits["maxFileBytes"])
        if text is None:
            files.append({"path": relative, "note": "deleted, binary, too large or not UTF-8: resolve it with git"})
            continue
        parsed = conflicts.parse(text)
        hunks = conflicts.index_base(root, run, relative, parsed["hunks"])
        entry: dict[str, Any] = {"path": relative, "hunks": len(hunks)}
        suggested = sum(1 for hunk in hunks if conflicts.suggest(hunk) is not None)
        if suggested:
            entry["suggested"] = suggested
        if parsed["leftovers"]:
            entry["leftoverMarkers"] = [item["line"] for item in parsed["leftovers"]]
        if not hunks and not parsed["leftovers"]:
            entry["note"] = "no markers left: stage it with `git add` when it is right"
        files.append(entry)
    leftover: list[dict[str, Any]] = []
    for relative in [p for p in conflicts.changed_paths(root, run) if p not in unmerged][:MAX_SCANNED]:
        text = read_text(root / relative, limits["maxFileBytes"])
        if text is None or not conflicts.has_markers(text):
            continue
        parsed = conflicts.parse(text)
        lines = sorted({hunk["start"] + 1 for hunk in parsed["hunks"]} | {i["line"] for i in parsed["leftovers"]})
        leftover.append({"path": relative, "markerLines": lines[:20]})
    answer: dict[str, Any] = {"operation": state["operation"] if state else None}
    if state and state["message"]:
        answer["incoming"] = state["message"]
    answer["unmerged"] = files
    if len(unmerged) > cap:
        answer["moreUnmerged"] = len(unmerged) - cap
    if leftover:
        answer["markersInChangedFiles"] = leftover[:cap]
        answer["warning"] = "these files are no longer unmerged in git but still hold conflict markers"
    if not state and not unmerged and not leftover:
        answer["note"] = "no merge, rebase, cherry-pick or revert in progress, and no conflict markers in changed files"
    else:
        answer["next"] = NEXT_STEPS
    return answer


def hunk_view(hunk: conflicts.Hunk, parsed: conflicts.Parsed, limits: MergeSettings, side_limit: int) -> dict[str, Any]:
    """One hunk as numbered lines: context, ours, base (when known), theirs, context; with its suggestion and,
    when keeping both sides needs shared lines repeated, which ones."""
    lines = parsed["lines"]
    context = limits["contextLines"]
    ours_first = hunk["start"] + 2
    theirs_first = hunk["end"] - len(hunk["theirs"]) + 1
    base_in_file = conflicts.marker_of(lines[hunk["start"] + 1 + len(hunk["ours"])]) == conflicts.BASE
    entry: dict[str, Any] = {
        "hunk": hunk["number"],
        "lines": f"{hunk['start'] + 1}-{hunk['end'] + 1}",
        "ours": hunk["oursLabel"],
        "theirs": hunk["theirsLabel"],
        "size": {"ours": len(hunk["ours"]), "theirs": len(hunk["theirs"])},
    }
    suggestion = conflicts.suggest(hunk)
    if suggestion is not None:
        entry["suggestion"] = suggestion
    if hunk["ours"] and hunk["theirs"]:
        note = conflicts.both_variants(parsed, hunk, "both", limits["boundaryLines"])[0][0]
        if note != "plain":
            entry["keepingBoth"] = note
    before = lines[max(0, hunk["start"] - context) : hunk["start"]]
    entry["before"] = conflicts.numbered(before, hunk["start"] - len(before) + 1, context)
    entry["oursLines"] = conflicts.numbered(hunk["ours"], ours_first, side_limit)
    if hunk["base"] is not None:
        entry["size"]["base"] = len(hunk["base"])
        if not hunk["base"]:
            entry["baseLines"] = ["(empty: both sides added these lines)"]
        elif base_in_file:
            entry["baseLines"] = conflicts.numbered(hunk["base"], ours_first + len(hunk["ours"]) + 1, side_limit)
        else:  # from git's index, not in the file
            entry["baseLines"] = [
                f"base| {row.split('| ', 1)[-1]}" for row in conflicts.numbered(hunk["base"], 1, side_limit)
            ]
    entry["theirsLines"] = conflicts.numbered(hunk["theirs"], theirs_first, side_limit)
    entry["after"] = conflicts.numbered(lines[hunk["end"] + 1 : hunk["end"] + 1 + context], hunk["end"] + 2, context)
    return entry


def file_view(
    root: Path,
    run: conflicts.Runner,
    relative: str,
    limits: MergeSettings,
    *,
    hunk: int | None,
    max_lines: int | None,
) -> dict[str, Any]:
    """The hunks of one file (or the one numbered ``hunk``) with their lines and suggestions."""
    path = workspace_file(root, relative)
    text = read_text(path, limits["maxFileBytes"])
    if text is None:
        return {"error": f"{relative} is binary, too large or not UTF-8; resolve it with git"}
    parsed = conflicts.parse(text)
    hunks = conflicts.index_base(root, run, relative, parsed["hunks"])
    parsed = {**parsed, "hunks": hunks}
    if hunk is not None and hunk not in {h["number"] for h in hunks}:
        return {"error": f"{relative} has no hunk {hunk}; it has {len(hunks)}"}
    side_limit = max_lines or (limits["sideLines"] if hunk is not None else max(4, limits["sideLines"] // 4))
    shown = [h for h in hunks if hunk is None or h["number"] == hunk]
    answer: dict[str, Any] = {"path": relative, "hunks": len(hunks)}
    answer["detail"] = [hunk_view(h, parsed, limits, side_limit) for h in shown]
    if parsed["leftovers"]:
        answer["leftoverMarkers"] = parsed["leftovers"]
        answer["leftoverAdvice"] = LEFTOVER_ADVICE.replace("PATH", relative)
    if any(h["base"] is None for h in hunks):
        answer["tip"] = NO_BASE_TIP.replace("PATH", relative)
    if not hunks and not parsed["leftovers"]:
        answer["note"] = "no conflict markers in this file"
    return answer


# resolve_conflicts


class Plan(TypedDict):
    replacements: dict[int, list[str]]
    takes: dict[int, str]
    notes: dict[int, str]


def plan_resolutions(
    parsed: conflicts.Parsed, resolutions: Sequence[Mapping[str, Any]], *, suggested: bool, boundary: int
) -> Plan:
    """What replaces each named hunk (and, with ``suggested``, each other hunk that has a suggestion); a request
    that names no hunk, a hunk the file does not have, or an unknown choice is a ValueError."""
    by_number = {hunk["number"]: hunk for hunk in parsed["hunks"]}
    plan: Plan = {"replacements": {}, "takes": {}, "notes": {}}
    for item in resolutions:
        number = int(item.get("hunk", 0))
        if number not in by_number:
            msg = f"there is no hunk {number}; the file has {len(by_number)}"
            raise ValueError(msg)
        text, take = item.get("text"), item.get("take")
        if (text is None) == (take is None):
            msg = f"hunk {number}: give either `take` ({', '.join(conflicts.CHOICES)}) or `text`, not both"
            raise ValueError(msg)
        if text is not None:
            plan["replacements"][number] = conflicts.as_lines(str(text), parsed["newline"])
            plan["takes"][number] = "text"
            continue
        if take not in conflicts.CHOICES:
            msg = f"hunk {number}: `take` is one of {', '.join(conflicts.CHOICES)}"
            raise ValueError(msg)
        _take(plan, parsed, by_number[number], cast("conflicts.Choice", take), boundary)
    if suggested:
        for hunk in parsed["hunks"]:
            found = conflicts.suggest(hunk)
            if hunk["number"] in plan["takes"] or found is None:
                continue
            _take(plan, parsed, hunk, found["take"], boundary)
            note = plan["notes"].get(hunk["number"])
            plan["notes"][hunk["number"]] = f"suggested: {found['why']}" + (f"; {note}" if note else "")
    if not plan["takes"]:
        msg = "name at least one hunk to resolve (or set suggested to take every suggestion)"
        raise ValueError(msg)
    return plan


def _take(plan: Plan, parsed: conflicts.Parsed, hunk: conflicts.Hunk, choice: conflicts.Choice, boundary: int) -> None:
    """Put ``choice`` for ``hunk`` in the plan; keeping both sides starts from the join the indentation points at."""
    number = hunk["number"]
    plan["takes"][number] = choice
    if choice in {"both", "theirs-then-ours"} and hunk["ours"] and hunk["theirs"]:
        note, lines = conflicts.both_variants(parsed, hunk, choice, boundary)[0]
        plan["replacements"][number] = lines
        if note != "plain":
            plan["notes"][number] = note
    else:
        plan["replacements"][number] = conflicts.side_lines(hunk, choice)


def provisional(parsed: conflicts.Parsed, replacements: Mapping[int, Sequence[str]]) -> str:
    """The file with ``replacements`` applied and every other hunk taken as ours: what is parsed to check them,
    since a file with markers never parses."""
    ours = {hunk["number"]: hunk["ours"] for hunk in parsed["hunks"]}
    return conflicts.render(parsed, {**ours, **replacements})


def verify_plan(
    call: Call, root: Path, name: str, parsed: conflicts.Parsed, plan: Plan, *, boundary: int
) -> dict[str, Any]:
    """Check the planned file parses; when it does not, try the other ways of keeping both sides of each hunk
    resolved with both (in file order) and keep the first that parses. Updates ``plan`` in place."""
    first = parse_texts(call, root, name, [provisional(parsed, plan["replacements"])], declarations=False)[0]
    if first.get("ok"):
        return {"parses": True}
    by_number = {hunk["number"]: hunk for hunk in parsed["hunks"]}
    for number, take in sorted(plan["takes"].items()):
        if take not in {"both", "theirs-then-ours"}:
            continue
        variants = conflicts.both_variants(parsed, by_number[number], cast("conflicts.Choice", take), boundary)
        texts = [provisional(parsed, {**plan["replacements"], number: lines}) for _, lines in variants]
        for (note, lines), result in zip(
            variants, parse_texts(call, root, name, texts, declarations=False), strict=True
        ):
            if result.get("ok"):
                plan["replacements"][number] = lines
                if note == "plain":
                    plan["notes"].pop(number, None)
                else:
                    plan["notes"][number] = note
                return {"parses": True}
    return {"parses": False, "errors": _errors(first)}


def load_conflicted(
    root: Path, run: conflicts.Runner, relative: str, limits: MergeSettings
) -> tuple[Path, str, conflicts.Parsed] | dict[str, Any]:
    """The file, its text and its hunks (bases filled in from the index), or the error that stops a resolution."""
    path = workspace_file(root, relative)
    text = read_text(path, limits["maxFileBytes"])
    if text is None:
        return {"error": f"{relative} is binary, too large or not UTF-8; resolve it with git"}
    parsed = conflicts.parse(text)
    if parsed["leftovers"]:
        return {
            "error": f"{relative} has conflict markers outside complete hunks",
            "leftoverMarkers": parsed["leftovers"],
            "advice": LEFTOVER_ADVICE.replace("PATH", relative),
        }
    if not parsed["hunks"]:
        return {"error": f"{relative} has no conflict hunks"}
    return (path, text, {**parsed, "hunks": conflicts.index_base(root, run, relative, parsed["hunks"])})


def check_plan(
    call: Call, root: Path, path: Path, parsed: conflicts.Parsed, plan: Plan, *, remaining: int, boundary: int
) -> dict[str, Any]:
    """Whether the planned Groovy file parses (``not checked`` with the reason otherwise)."""
    if not is_groovy_source(path):
        return {"parses": "not checked: not a Groovy source"}
    try:
        check = verify_plan(call, root, path.name, parsed, plan, boundary=boundary)
    except Exception as exc:  # the engine not running, or an older one without groovyscope/parse
        return {"parses": f"not checked: {exc}"}
    if remaining and check.get("parses") is True:
        check["note"] = f"checked with the {remaining} unresolved hunk(s) taken as ours"
    return check


def write_resolution(
    path: Path, relative: str, before: str, after: str, *, hunks: int, confirm_policy: str
) -> dict[str, Any]:
    """Write the resolved text after the user's confirmation (when the client can ask), never over a newer save."""
    question = interaction.write_question(
        f"Resolve {hunks} conflict hunk(s)", [(relative, edits.changed_line_count(before, after))]
    )
    decision = interaction.confirm(question, policy=confirm_policy)
    if decision in {interaction.DECLINED, interaction.CANCELLED}:
        return {"written": False, "confirmation": decision}
    try:
        edits.write_document(path, after, expected=before)
    except edits.FileChangedError as exc:
        return {"written": False, "why": str(exc)}
    return {"written": True}


def resolve(
    root: Path,
    run: conflicts.Runner,
    call: Call,
    relative: str,
    resolutions: Sequence[Mapping[str, Any]],
    *,
    limits: MergeSettings,
    suggested: bool,
    apply: bool,
    force: bool,
    confirm_policy: str,
) -> dict[str, Any]:
    """resolve_conflicts: plan, verify (Groovy files), and write when asked."""
    loaded = load_conflicted(root, run, relative, limits)
    if isinstance(loaded, dict):
        return loaded
    path, text, parsed = loaded
    try:
        plan = plan_resolutions(parsed, resolutions, suggested=suggested, boundary=limits["boundaryLines"])
    except ValueError as exc:
        return {"error": str(exc)}
    remaining = [h["number"] for h in parsed["hunks"] if h["number"] not in plan["takes"]]
    check = check_plan(call, root, path, parsed, plan, remaining=len(remaining), boundary=limits["boundaryLines"])
    answer: dict[str, Any] = {"path": relative, "resolved": _resolved_rows(parsed, plan, limits), **check}
    answer["remaining"] = remaining
    if not apply:
        return {**answer, "written": False, "note": "dry run: set apply to write"}
    if check.get("parses") is False and not force:
        why = "the result does not parse; change the choices, give `text` for the hunk, or set force"
        return {**answer, "written": False, "why": why}
    after = conflicts.render(parsed, plan["replacements"])
    answer.update(
        write_resolution(path, relative, text, after, hunks=len(plan["takes"]), confirm_policy=confirm_policy)
    )
    if answer["written"] and not remaining:
        answer["next"] = f"no hunks left: run merge_review, then `git add {relative}`"
    return answer


def _resolved_rows(parsed: conflicts.Parsed, plan: Plan, limits: MergeSettings) -> list[dict[str, Any]]:
    """Each resolved hunk with its line range in the result, its note, and for a join of both sides the lines
    around the join, so the boundary can be checked without reading the file."""
    rows: list[dict[str, Any]] = []
    shift = 0
    for hunk in parsed["hunks"]:
        number = hunk["number"]
        if number not in plan["replacements"]:
            continue
        lines = plan["replacements"][number]
        start = hunk["start"] + shift + 1
        row: dict[str, Any] = {"hunk": number, "take": plan["takes"][number]}
        row["lines"] = f"{start}-{start + len(lines) - 1}" if lines else f"removed at {start}"
        if number in plan["notes"]:
            row["note"] = plan["notes"][number]
        if plan["takes"][number] in {"both", "theirs-then-ours"}:
            first, second = (
                (hunk["ours"], hunk["theirs"]) if plan["takes"][number] == "both" else (hunk["theirs"], hunk["ours"])
            )
            repeated = len(lines) - len(first) - len(conflicts.new_imports(second, first))
            around = max(0, len(first) - 2)
            join = lines[around : len(first) + repeated + 2]
            row["join"] = conflicts.numbered(join, start + around, limits["sideLines"])
        rows.append(row)
        shift += len(lines) - (hunk["end"] - hunk["start"] + 1)
    return rows


# merge_review


class Refs(TypedDict):
    result: str | None  # None: the working tree
    ours: str
    theirs: str
    base: str | None
    operation: str


def review_refs(root: Path, run: conflicts.Runner) -> Refs | None:
    """What to compare: the operation in progress (working tree against HEAD and the incoming commit), else a
    merge commit at HEAD (against its two parents); None when there is neither."""
    state = conflicts.operation(root, run)
    if state is not None:
        theirs = state["theirs"]
        if state["operation"] == "merge":
            base = conflicts.merge_base(root, run, "HEAD", theirs)
        elif state["operation"] == "revert":
            base, theirs = theirs, f"{theirs}^"
        else:
            base = conflicts.rev(root, run, f"{theirs}^")
        return {"result": None, "ours": "HEAD", "theirs": theirs, "base": base, "operation": state["operation"]}
    if conflicts.rev(root, run, "HEAD^2") is None:
        return None
    base = conflicts.merge_base(root, run, "HEAD^1", "HEAD^2")
    return {"result": "HEAD", "ours": "HEAD^1", "theirs": "HEAD^2", "base": base, "operation": "merge commit"}


def both_sides_changed(root: Path, run: conflicts.Runner, refs: Refs) -> list[str]:
    """Groovy files both sides changed since the base (where a clean merge can still be wrong), plus unmerged
    ones; every changed Groovy file when the base is unknown."""

    def names(*args: str) -> set[str]:
        try:
            done = run(["git", "diff", "--name-only", "-z", "--relative", *args], cwd=root, timeout=30.0)
        except (OSError, subprocess.SubprocessError):
            return set()
        return {name for name in done.stdout.split("\0") if name} if done.returncode == 0 else set()

    if refs["base"] is not None:
        found = names(refs["base"], refs["ours"]) & names(refs["base"], refs["theirs"])
    else:
        found = set(conflicts.changed_paths(root, run))
    found |= set(conflicts.unmerged_paths(root, run))
    return sorted(name for name in found if is_groovy_source(Path(name)))


Members = dict[str, tuple[str, str, int]]  # key -> (kind, normalised body, line)


def members_of(text: str, result: Mapping[str, Any]) -> Members:
    """Declarations by name (``Class.method``; overloads numbered ``#2``, ``#3``) with kind, body and line."""
    lines = text.splitlines()
    found: Members = {}
    for item in result.get("declarations") or []:
        name, kind = str(item.get("name")), str(item.get("kind"))
        start, end = int(item.get("line", 0)), int(item.get("endLine", item.get("line", 0)))
        body = "" if kind == "class" else " ".join(" ".join(lines[max(0, start - 1) : end]).split())
        key, count = name, 2
        while key in found:
            key, count = f"{name}#{count}", count + 1
        found[key] = (kind, body, start)
    return found


def compare(ours: Members, theirs: Members, base: Members | None, result: Members) -> list[dict[str, Any]]:
    """Findings for one file: declarations lost, deletions undone, changes dropped, and members both sides changed
    where the result keeps one side or mixes them. Without a base, a deletion cannot be told from a loss and an
    old version from a new one, so those findings say so."""
    rows: list[dict[str, Any]] = []
    for key in sorted(set(ours) | set(theirs) | set(result)):
        kind, _, line = result.get(key) or ours.get(key) or theirs.get(key) or ("", "", 0)
        row: dict[str, Any] = {"symbol": key, "kind": kind, "line": line}
        finding = _member_finding(key, ours, theirs, base, result)
        if finding is not None:
            rows.append({**row, "finding": finding[0], "detail": finding[1]})
    return rows


def _member_finding(
    key: str, ours: Members, theirs: Members, base: Members | None, result: Members
) -> tuple[str, str] | None:
    in_o, in_t = key in ours, key in theirs
    one_side_deleted = base is not None and key in base and not (in_o and in_t)
    if key not in result:
        if one_side_deleted:
            return None  # a deletion the result carries
        return ("lost", f"in {_sides(in_o, in_t)}, not in the result" + ("" if base is not None else " (no base)"))
    if one_side_deleted and base is not None:
        return _deletion_finding(key, ours, theirs, base)
    if not (in_o and in_t) or result[key][0] == "class":
        return None
    return _body_finding(ours[key][1], theirs[key][1], result[key][1], base[key][1] if base and key in base else None)


def _deletion_finding(key: str, ours: Members, theirs: Members, base: Members) -> tuple[str, str]:
    """A member one side deleted that the result keeps."""
    survivor = ours.get(key) or theirs.get(key)
    deleter = _sides(key not in ours, key not in theirs)
    if survivor is None or survivor[1] == base[key][1]:
        return ("deletion undone", f"{deleter} deleted it; the result keeps it")
    return ("kept after deletion", f"{deleter} deleted it, the other side changed it; the result keeps it")


def _body_finding(o: str, t: str, r: str, b: str | None) -> tuple[str, str] | None:
    """A member both sides have, compared by body: ours, theirs, result and base (None when unknown)."""
    if o == t:
        return None if r == o else ("changed in the merge", "both sides agree; the result differs from both")
    if b is not None and b in {o, t}:
        changed, expected = ("ours", o) if t == b else ("theirs", t)
        return None if r == expected else ("change dropped", f"{changed} changed it; the result does not have it")
    if r not in {o, t}:
        return ("blended", "the sides differ; the result mixes them: read it")
    kept, other = ("ours", "theirs") if r == o else ("theirs", "ours")
    if b is None:
        return ("one side kept", f"the sides differ (no base); the result keeps {kept}")
    return ("one side kept", f"both sides changed it; the result keeps {kept}, {other}'s change is gone")


def _sides(in_ours: bool, in_theirs: bool) -> str:
    return "ours and theirs" if in_ours and in_theirs else "ours" if in_ours else "theirs"


def review(
    root: Path, run: conflicts.Runner, call: Call, paths: Sequence[str] | None, *, limits: MergeSettings, cap: int
) -> dict[str, Any]:
    """merge_review: per Groovy file, the declarations of the result against ours, theirs and their base."""
    refs = review_refs(root, run)
    if refs is None:
        return {"error": "no merge, rebase, cherry-pick or revert in progress, and HEAD is not a merge commit"}
    targets = list(paths) if paths else both_sides_changed(root, run, refs)
    files: list[dict[str, Any]] = []
    for relative in targets[: limits["reviewFiles"]]:
        files.append(review_file(root, run, call, relative, refs=refs, limits=limits))
    answer: dict[str, Any] = {"operation": refs["operation"], "base": refs["base"] is not None}
    flagged = [entry for entry in files if entry.get("findings") or entry.get("error") or entry.get("parses") is False]
    answer["files"] = len(files)
    answer["clean"] = len(files) - len(flagged)
    answer["flagged"] = flagged[:cap]
    if len(targets) > limits["reviewFiles"]:
        answer["notReviewed"] = targets[limits["reviewFiles"] :][:cap]
    answer["next"] = (
        "a clean review means no declaration was lost and no one sided change was dropped; it cannot see a test "
        "that now asserts the other side's behaviour: run the affected tests before committing"
    )
    return answer


def review_file(
    root: Path, run: conflicts.Runner, call: Call, relative: str, *, refs: Refs, limits: MergeSettings
) -> dict[str, Any]:
    """One file of merge_review."""
    entry: dict[str, Any] = {"path": relative}
    if refs["result"] is None:
        text = read_text(root / relative, limits["maxFileBytes"])
    else:
        text = conflicts.show(root, run, f"{refs['result']}:./{relative}")
    if text is None:
        entry["note"] = "not in the result (deleted), or too large to review"
        return entry
    if conflicts.has_markers(text):
        entry["error"] = "still has conflict markers: resolve them first"
        return entry
    versions = {
        "ours": conflicts.show(root, run, f"{refs['ours']}:./{relative}"),
        "theirs": conflicts.show(root, run, f"{refs['theirs']}:./{relative}"),
        "base": conflicts.show(root, run, f"{refs['base']}:./{relative}") if refs["base"] else None,
    }
    present = {name: version for name, version in versions.items() if version is not None}
    try:
        parsed = parse_texts(call, root, Path(relative).name, [text, *present.values()], declarations=True)
    except Exception as exc:  # the engine not running, or an older one without groovyscope/parse
        entry["error"] = f"not reviewed: {exc}"
        return entry
    if not parsed[0].get("ok"):
        entry["parses"] = False
        entry["errors"] = _errors(parsed[0])
        return entry
    members = {
        name: members_of(version, result) for (name, version), result in zip(present.items(), parsed[1:], strict=True)
    }
    result_members = members_of(text, parsed[0])
    base_members = members.get("base") if "base" in present else ({} if refs["base"] else None)
    findings = compare(members.get("ours", {}), members.get("theirs", {}), base_members, result_members)
    entry["members"] = {name: len(found) for name, found in {**members, "result": result_members}.items()}
    if findings:
        entry["findings"] = findings
    return entry


def merge_tools(
    root: Path,
    call: Call,
    cap: int,
    settings: Mapping[str, Any],
    *,
    confirm_policy: str = interaction.POLICY_AUTO,
    run: conflicts.Runner = process.run_text,
) -> dict[str, Callable[..., Any]]:
    """The tools, bound to one workspace and engine session."""
    limits = merge_settings(settings)

    def merge_conflicts(
        path: str | None = None, hunk: int | None = None, max_lines: int | None = None
    ) -> dict[str, Any]:
        """Without PATH: the merge, rebase or cherry-pick in progress, each unmerged file with its hunk count, and
        changed files that still hold markers (from git, not a grep of the tree). With PATH: its hunks as numbered
        lines (ours, base when known, theirs, context), each with a suggestion where one side left the lines
        unchanged and, when keeping both sides needs the lines git moved out of the hunk repeated, which ones.
        HUNK shows one hunk with up to MAX_LINES lines per side."""
        if path is None:
            return status(root, run, limits, cap)
        try:
            return file_view(root, run, path, limits, hunk=hunk, max_lines=max_lines)
        except ValueError as exc:
            return {"error": str(exc)}

    def resolve_conflicts(
        path: str,
        resolutions: list[HunkResolution] | None = None,
        suggested: bool = False,
        apply: bool = False,
        force: bool = False,
    ) -> dict[str, Any]:
        """Resolve conflict hunks of PATH in one write. RESOLUTIONS: [{hunk: N, take: ours | theirs | both |
        theirs-then-ours | base | neither}] or [{hunk: N, text: "..."}]; SUGGESTED takes every suggestion
        merge_conflicts gives for the hunks not named. Both keeps ours then theirs, each import once, repeating
        the closing lines git moved out of the hunk when the parser needs them. A Groovy result is parsed first
        (unresolved hunks taken as ours); APPLY writes it only when it parses (FORCE overrides). Never stages."""
        try:
            return resolve(
                root,
                run,
                call,
                path,
                cast("list[Mapping[str, Any]]", resolutions or []),
                limits=limits,
                suggested=suggested,
                apply=apply,
                force=force,
                confirm_policy=confirm_policy,
            )
        except ValueError as exc:
            return {"error": str(exc)}

    def merge_review(paths: list[str] | None = None) -> dict[str, Any]:
        """After resolving (or after committing a merge): in the Groovy files both sides changed (or PATHS), the
        declarations the result lost, deletions it undid, one sided changes it dropped and members both sides
        changed where it keeps only one side, from the parser, against ours, theirs and their merge base."""
        return review(root, run, call, paths, limits=limits, cap=cap)

    tools: dict[str, Callable[..., Any]] = {
        "merge_conflicts": merge_conflicts,
        "resolve_conflicts": resolve_conflicts,
        "merge_review": merge_review,
    }
    for tool in tools.values():
        # Resolved here, where HunkResolution is defined: the MCP SDK reads the signature through the server's
        # wrappers, whose module does not know the name.
        tool.__signature__ = inspect.signature(tool, eval_str=True)  # type: ignore[attr-defined]
    return tools
