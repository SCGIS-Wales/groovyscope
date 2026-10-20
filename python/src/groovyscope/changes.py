"""What a merge request changed, from git, as the lines each file gained or altered; and the pure rules that turn
findings, declarations and risky constructs on those files into a review: blocking, should fix, by design.

Only git plumbing runs here (``git merge-base`` and ``git diff --unified=0``), never a build. Everything that needs
the engine is composed by the ``review_changes`` tool and the ``review --changed`` command on top of these functions.
"""

from __future__ import annotations

import re
import shutil
import subprocess
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any, TypedDict

from groovyscope import practices, process
from groovyscope.paths import GROOVY_SUFFIXES

Runner = Callable[[Sequence[str], Path], tuple[int, str]]
Which = Callable[[str], str | None]

GIT_TIMEOUT_SECONDS = 60
# Java joins the review for the impact of its declarations and the tests a change reaches (src/it/java and the like);
# the engine reports no lint findings on it.
REVIEWED_SUFFIXES = (*GROOVY_SUFFIXES, ".java")
REVIEWED_NAMES = ("Jenkinsfile",)
HUNK = re.compile(r"^@@ -(?P<old>\d+)(?:,(?P<old_count>\d+))? \+(?P<new>\d+)(?:,(?P<new_count>\d+))? @@")
BLOCKING_GROUPS = frozenset({"security", "runtime"})
RISKY_KINDS = ("catch", "def", "return-null")


class ChangedFile(TypedDict):
    """One reviewed file and the 1-based, inclusive line ranges the change added or altered in it."""

    path: str
    status: str  # added | modified | deleted
    ranges: list[list[int]]


class ChangeSet(TypedDict):
    """The change under review."""

    base: str
    mode: str  # branch | staged
    files: list[ChangedFile]


def default_runner(command: Sequence[str], cwd: Path) -> tuple[int, str]:
    """Run git; stdout on success, stderr on failure, a non-zero code for anything that went wrong."""
    try:
        done = process.run_text(command, cwd=cwd, timeout=GIT_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired:
        return (-1, f"{command[0]} did not finish within {GIT_TIMEOUT_SECONDS} s")
    except OSError as exc:
        return (-1, str(exc))
    return (done.returncode, done.stdout if done.returncode == 0 else (done.stderr or done.stdout))


def reviewable(path: str) -> bool:
    name = path.rsplit("/", 1)[-1]
    return path.endswith(REVIEWED_SUFFIXES) or any(name == n or name.startswith(n + ".") for n in REVIEWED_NAMES)


def parse_unified_diff(text: str) -> list[ChangedFile]:
    """Files and new-side line ranges from ``git diff --unified=0`` output. A hunk that only deletes lines adds no
    range (there is nothing on the new side to point at) but still marks the file as modified."""
    files: list[ChangedFile] = []
    current: ChangedFile | None = None
    old_path: str | None = None
    for line in text.splitlines():
        if line.startswith("--- "):
            old_path = line[4:].strip()
            continue
        if line.startswith("+++ "):
            new_path = line[4:].strip()
            if new_path == "/dev/null":
                path = old_path[2:] if old_path and old_path.startswith("a/") else (old_path or "")
                current = {"path": path, "status": "deleted", "ranges": []}
            else:
                path = new_path[2:] if new_path.startswith("b/") else new_path
                status = "added" if old_path == "/dev/null" else "modified"
                current = {"path": path, "status": status, "ranges": []}
            files.append(current)
            continue
        match = HUNK.match(line)
        if match and current is not None and current["status"] != "deleted":
            count = int(match.group("new_count") or 1)
            if count > 0:
                start = int(match.group("new"))
                current["ranges"].append([start, start + count - 1])
    return files


# Tried in order when no base is given and origin/HEAD names none: the usual default branch names.
BASE_CANDIDATES = ("origin/main", "origin/master", "main", "master")


def default_base(root: Path, runner: Runner) -> str | None:
    """The repository's default branch: what ``origin/HEAD`` points at (``origin/master``), else the first of the
    usual names that exists; None when there is none."""
    code, output = runner(["git", "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD"], root)
    if code == 0 and output.strip():
        return output.strip()
    for candidate in BASE_CANDIDATES:
        code, _ = runner(["git", "rev-parse", "--verify", "--quiet", f"{candidate}^{{commit}}"], root)
        if code == 0:
            return candidate
    return None


def changed_files(
    root: Path,
    base: str | None = None,
    *,
    staged: bool = False,
    runner: Runner = default_runner,
    which: Which = shutil.which,
) -> ChangeSet:
    """The reviewable files the branch (or the index, with ``staged``) changed against ``base`` (the repository's
    default branch when not given), with their changed line ranges. Raises ``ValueError`` with a reason a person
    can act on."""
    if which("git") is None:
        msg = "git is not installed, so the changed files cannot be listed; pass paths to review instead"
        raise ValueError(msg)
    code, output = runner(["git", "rev-parse", "--show-toplevel"], root)
    if code != 0:
        msg = f"{root} is not inside a git repository ({output.strip()})"
        raise ValueError(msg)
    common = ["git", "diff", "--unified=0", "--no-color", "--no-ext-diff", "--no-renames", "--relative"]
    detected = None if staged else default_base(root, runner)
    chosen = base or detected or ""
    if staged:
        code, output = runner([*common, "--cached", "--"], root)
        mode = "staged"
    else:
        if not chosen:
            msg = "no default branch found (no origin/HEAD, main or master); pass the branch the request targets"
            raise ValueError(msg)
        code, merge_base = runner(["git", "merge-base", chosen, "HEAD"], root)
        if code != 0:
            hint = f"this repository's default branch is {detected}" if detected else "pass the branch it targets"
            msg = f"unknown base {chosen!r} ({merge_base.strip()}); {hint}"
            raise ValueError(msg)
        code, output = runner([*common, merge_base.strip(), "--"], root)
        mode = "branch"
    if code != 0:
        msg = f"git diff failed: {output.strip()}"
        raise ValueError(msg)
    files = [f for f in parse_unified_diff(output) if reviewable(f["path"])]
    return {"base": chosen if mode == "branch" else "index", "mode": mode, "files": files}


def fix_scope(root: Path, change: ChangeSet) -> dict[str, Any]:
    """The ``uris`` and ``lines`` (per URI, 1-based inclusive ranges) of a change set, for ``groovyscope/fix``:
    safe fixes only on the lines the branch changed, so existing code is never touched."""
    lines = {
        (root / f["path"]).resolve().as_uri(): [list(r) for r in f["ranges"]]
        for f in change["files"]
        if f["status"] != "deleted" and not f["path"].endswith(".java")
    }
    return {"uris": list(lines), "lines": lines}


def in_ranges(line: int, ranges: Sequence[Sequence[int]]) -> bool:
    return any(start <= line <= end for start, end in ranges)


def ranges_by_path(change: ChangeSet) -> dict[str, list[list[int]]]:
    return {f["path"]: f["ranges"] for f in change["files"] if f["status"] != "deleted"}


def split_findings(
    findings: Sequence[Mapping[str, Any]], ranges: Mapping[str, Sequence[Sequence[int]]]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Findings on the changed lines, and the rest of the findings on the changed files (pre-existing)."""
    inside: list[dict[str, Any]] = []
    elsewhere: list[dict[str, Any]] = []
    for finding in findings:
        path = str(finding.get("path", ""))
        if in_ranges(int(finding.get("line", 0)), ranges.get(path, ())):
            inside.append(dict(finding))
        else:
            elsewhere.append(dict(finding))
    return (inside, elsewhere)


def bucket(findings: Sequence[Mapping[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    """Blocking: errors, and anything from the security or runtime-profile groups. Should fix: warnings. Nits: the
    rest. Each bucket keeps its findings worst first."""
    buckets: dict[str, list[dict[str, Any]]] = {"blocking": [], "shouldFix": [], "nits": []}
    for finding in findings:
        code = str(finding.get("code") or "")
        severity = str(finding.get("severity") or "info")
        if severity == "error" or practices.group_of(code) in BLOCKING_GROUPS:
            buckets["blocking"].append(dict(finding))
        elif severity == "warning":
            buckets["shouldFix"].append(dict(finding))
        else:
            buckets["nits"].append(dict(finding))
    for items in buckets.values():
        items.sort(key=lambda f: (practices.level(f), str(f.get("path")), int(f.get("line", 0))))
    return buckets


def touched_declarations(
    outline: Sequence[Mapping[str, Any]], ranges: Sequence[Sequence[int]], *, limit: int
) -> list[dict[str, Any]]:
    """Declarations whose lines overlap the changed ranges: what callers elsewhere may feel. ``outline`` is the
    flat document outline the MCP ``document_symbols`` tool returns (``name``, ``kind``, ``lines``, ``in``)."""
    found: list[dict[str, Any]] = []
    for entry in outline:
        lines = entry.get("lines") or [0, 0]
        start, end = int(lines[0] or 0), int(lines[1] or lines[0] or 0)
        kind = str(entry.get("kind") or "")
        if kind not in {"class", "interface", "trait", "enum", "record", "method", "constructor", "field", "property"}:
            continue
        if any(not (end < r0 or start > r1) for r0, r1 in ranges):
            container = entry.get("in")
            symbol = f"{container}.{entry['name']}" if container else str(entry["name"])
            found.append({"symbol": symbol, "kind": kind, "lines": [start, end]})
        if len(found) >= limit:
            break
    return found


# A catch is risky only when the failure disappears: an empty block, or a default returned in its place without a
# log. A catch that logs, rethrows or reacts otherwise is deliberate handling a reviewer need not question.
RISKY_HANDLING = frozenset({"swallowed", "returns-default"})


def risky_in_change(
    matches: Sequence[Mapping[str, Any]], ranges: Mapping[str, Sequence[Sequence[int]]]
) -> list[dict[str, Any]]:
    """Structural matches (catch, def, return null) that sit on changed lines; catches only when they hide the
    failure (see :data:`RISKY_HANDLING`)."""
    return [
        dict(m)
        for m in matches
        if in_ranges(int(m.get("line", 0)), ranges.get(str(m.get("path", "")), ()))
        and (m.get("handling") is None or m.get("handling") in RISKY_HANDLING)
    ]


# Practice groups ranked for a reviewer: risks change behaviour, smells mislead the next reader, noise is style.
RISK_GROUPS = frozenset({"security", "runtime", "truth", "numbers", "boundaries", "errors", "concurrency", "versions"})
SMELL_GROUPS = frozenset({"naming", "immutability", "tests", "strings"})
TIERS = ("risks", "smells", "noise")


def tier_of(code: str) -> str:
    group = practices.group_of(code)
    return "risks" if group in RISK_GROUPS else "smells" if group in SMELL_GROUPS else "noise"


def triage(findings: Sequence[Mapping[str, Any]], *, limit: int) -> dict[str, Any]:
    """The changed-line findings ranked as a reviewer reads them: per tier the codes, most frequent first, with how
    many of each the safe fixes repair, and the one command that applies them."""
    per_code: dict[str, dict[str, Any]] = {}
    for finding in findings:
        code = str(finding.get("code") or "")
        entry = per_code.setdefault(code, {"code": code, "group": practices.group_of(code), "count": 0, "fixable": 0})
        entry["count"] += 1
        entry["fixable"] += 1 if finding.get("fixable") else 0
    tiers: dict[str, Any] = {}
    for tier in TIERS:
        codes = sorted((e for e in per_code.values() if tier_of(e["code"]) == tier), key=lambda e: -e["count"])
        tiers[tier] = {"count": sum(e["count"] for e in codes), "codes": codes[:limit]}
    fixable = sorted(code for code, e in per_code.items() if e["fixable"])
    total = sum(e["fixable"] for e in per_code.values())
    if fixable:
        # The CLI's option is `--code`, repeated; `fix` only reports until `--apply` is added.
        options = " ".join(f"--code {code}" for code in fixable)
        tiers["fix"] = {"count": total, "command": f"groovyscope fix --changed {options}"}
    return tiers


def verdict(buckets: Mapping[str, Sequence[Any]], risky: Sequence[Any], impact: Sequence[Mapping[str, Any]]) -> str:
    """One sentence a reviewer would write first."""
    blocking = len(buckets.get("blocking", ()))
    should = len(buckets.get("shouldFix", ()))
    high = sum(1 for report in impact if report.get("riskLevel") == "high")
    if blocking:
        return f"request changes: {blocking} blocking finding(s) on the changed lines"
    parts = []
    if should:
        parts.append(f"{should} warning(s) to fix")
    if risky:
        parts.append(f"{len(risky)} risky construct(s) added")
    if high:
        parts.append(f"{high} changed declaration(s) with high impact")
    if parts:
        return "approve with comments: " + ", ".join(parts)
    return "approve: nothing blocking on the changed lines"


# The wording of merge request comments the agent posts (GitLab inline discussions): respectful and inviting,
# one observation and one concrete suggestion each, the reason stated, never a verdict on the author.
COMMENT_OPENERS = {
    "blocking": "This needs a change before we merge",
    "shouldFix": "Worth fixing in this merge request",
    "nits": "Nit, optional",
}
RISKY_QUESTIONS = {
    "catch": "A new `catch` block arrived here. Could we make sure the handling is deliberate: rethrow, log with "
    "context, or return a documented default? Swallowed exceptions are the hardest bugs to find later.",
    "def": "This new declaration uses `def`. Could we give it a declared type? Static typing lets the compiler, "
    "the IDE and the reviewers catch mistakes before runtime.",
    "return-null": "This new `return null` can surprise callers. Could we return an empty value, an Optional, or "
    "document the null case where the method is declared?",
}


class Comment(TypedDict):
    """One comment to post on the merge request: ``path`` and ``line`` place it on the diff; ``kind`` is
    blocking, shouldFix, nits, risky or uncovered."""

    path: str
    line: int
    kind: str
    body: str


def finding_comment(finding: Mapping[str, Any], kind: str) -> Comment:
    code = str(finding.get("code") or finding.get("source") or "finding")
    message = str(finding.get("message") or "").strip().rstrip(".")
    body = f"{COMMENT_OPENERS[kind]}: {message} (`{code}`)."
    if finding.get("fixable"):
        body += " groovyscope can apply this one mechanically, so it costs nothing to accept."
    body += f" `groovyscope explain {code}` says why it matters and shows the fix."
    return {"path": str(finding.get("path") or ""), "line": int(finding.get("line") or 1), "kind": kind, "body": body}


def risky_comment(site: Mapping[str, Any]) -> Comment:
    kind = str(site.get("kind") or "")
    detail = site.get("meaning") or site.get("detail") or site.get("handling")
    body = RISKY_QUESTIONS.get(kind, f"A new `{kind}` construct arrived here; is it intended?")
    if detail:
        body += f" (groovyscope sees: {detail}.)"
    return {"path": str(site.get("path") or ""), "line": int(site.get("line") or 1), "kind": "risky", "body": body}


def uncovered_comment(gap: Mapping[str, Any]) -> Comment:
    name = str(gap.get("name") or gap.get("symbol") or "this method")
    missed = gap.get("missedLines") or gap.get("missed")
    detail = f" ({missed} line(s) never executed)" if missed else ""
    body = (
        f"No test executes `{name}` yet{detail}. A test for the new behaviour would protect this change the next "
        "time someone touches it; happy to help draft one."
    )
    return {"path": str(gap.get("path") or ""), "line": int(gap.get("line") or 1), "kind": "uncovered", "body": body}


def draft_comments(
    buckets: Mapping[str, Sequence[Mapping[str, Any]]],
    risky: Sequence[Mapping[str, Any]],
    uncovered: Sequence[Mapping[str, Any]],
    *,
    limit: int,
) -> list[Comment]:
    """The inline comments for a merge request, most important first and never more than ``limit``: blocking
    findings, then warnings, then risky constructs, then uncovered methods, then nits."""
    drafted: list[Comment] = []
    drafted += [finding_comment(f, "blocking") for f in buckets.get("blocking", ())]
    drafted += [finding_comment(f, "shouldFix") for f in buckets.get("shouldFix", ())]
    drafted += [risky_comment(site) for site in risky]
    drafted += [uncovered_comment(gap) for gap in uncovered]
    drafted += [finding_comment(f, "nits") for f in buckets.get("nits", ())]
    return drafted[: max(0, limit)]


def summary_comment(
    verdict: str,
    counts: Mapping[str, int],
    impact: Sequence[Mapping[str, Any]],
    uncovered_count: int,
    comments: int,
) -> str:
    """The note that goes on the merge request itself: thanks, the verdict in plain words, the numbers, and what
    the reviewer looked at, so the author knows the comments are complete."""
    blocking = int(counts.get("blocking", 0))
    should = int(counts.get("shouldFix", 0))
    nits = int(counts.get("nits", 0))
    high = [r for r in impact if r.get("riskLevel") == "high"]
    lines = ["Thanks for this merge request. I reviewed the changed lines with groovyscope; here is where it stands."]
    if blocking:
        lines.append(
            f"There are {blocking} finding(s) to resolve before merging; each has an inline comment with the fix."
        )
    elif should or nits:
        lines.append("Nothing blocks the merge. The inline comments are suggestions, the nits are optional.")
    else:
        lines.append("Nothing blocks the merge and I found nothing to fix on the changed lines. Nice work.")
    if should or nits:
        lines.append(f"Suggestions: {should} worth fixing here, {nits} nit(s).")
    if high:
        names = ", ".join(f"`{r.get('symbol') or r.get('name')}`" for r in high[:3])
        lines.append(f"Changed declarations with many callers: {names}. Please run their callers' tests too.")
    if uncovered_count:
        lines.append(f"Tests: {uncovered_count} changed method(s) have no test executing them yet.")
    lines.append(f"Verdict: {verdict}. ({comments} inline comment(s) posted.)")
    return " ".join(lines)
