"""The edit gate's verdict on a candidate text: what a write would change, which of the findings on the result it
introduces, and whether that blocks the write, warns, or passes.

Only findings the write introduces count: a file that already has errors must not lock the agent out of fixing
them. A finding counts as introduced when the candidate has more of its code and message than the current text
has (lines move in an edit, so they are not compared). In ``block`` mode a syntax error or an introduced error
blocks; in ``warn`` mode the same is reported and the write goes through. Pure: the hook gathers the findings.
"""

from __future__ import annotations

import difflib
from collections import Counter
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any, Literal, TypedDict

from groovyscope import config as config_module
from groovyscope import mcp_reuse
from groovyscope.source_lines import line_kinds

Decision = Literal["block", "warn", "pass"]
ERROR_LEVEL = 1
SHOWN = 8  # findings named in a verdict; the rest are counted
INCLUDE = ["compile", "lint", "practices", "types"]  # types: the static type check of the file
EngineCall = Callable[[str, dict[str, Any]], Mapping[str, Any]]


class GateSettings(TypedDict):
    enabled: bool
    mode: str  # block | warn
    trustPlans: bool
    requirePlan: bool
    requirePlanAboveLines: int


class CommentPolicy(TypedDict):
    maxAddedShare: float
    minAddedLines: int
    exemptPublicJavadoc: bool


class Change(TypedDict):
    addedLines: int
    removedLines: int
    addedCodeLines: int
    addedCommentLines: int
    addedComments: list[int]  # 1-based lines of the candidate
    exemptJavadocLines: int  # one added Javadoc line per non-private member, left out of the comment share


class Verdict(TypedDict):
    decision: Decision
    reasons: list[str]
    introduced: list[dict[str, Any]]
    existing: int
    change: Change
    advice: list[str]  # what the write should reconsider without being held back (GS081, GS082)


def settings(effective: Mapping[str, Any]) -> GateSettings:
    """``hooks.preWrite`` with the defaults filled in."""
    section: Mapping[str, Any] = (effective.get("hooks") or {}).get("preWrite") or {}
    defaults: Mapping[str, Any] = config_module.default("hooks", "preWrite")

    def value(key: str) -> Any:
        return section.get(key, defaults[key])

    return {
        "enabled": bool(value("enabled")),
        "mode": str(value("mode")),
        "trustPlans": bool(value("trustPlans")),
        "requirePlan": bool(value("requirePlan")),
        "requirePlanAboveLines": int(value("requirePlanAboveLines")),
    }


def comment_policy(effective: Mapping[str, Any]) -> CommentPolicy:
    """The ``comments`` keys the gate reads, with the defaults filled in."""
    section: Mapping[str, Any] = effective.get("comments") or {}

    def value(key: str) -> Any:
        return section.get(key, config_module.default("comments", key))

    return {
        "maxAddedShare": float(value("maxAddedShare")),
        "minAddedLines": int(value("minAddedLines")),
        "exemptPublicJavadoc": bool(value("exemptPublicJavadoc")),
    }


def reuse_settings(effective: Mapping[str, Any]) -> dict[str, Any] | None:
    """What the gate's reuse check reads (``reuse``, ``duplicates``), or None when ``reuse.checkWrites`` is off."""
    section: Mapping[str, Any] = effective.get("reuse") or {}
    if not section.get("checkWrites", config_module.default("reuse", "checkWrites")):
        return None
    return {"reuse": dict(section), "duplicates": dict(effective.get("duplicates") or {})}


def exempt_javadoc(after: str, added: set[int], javadoc: Sequence[tuple[int, int]], code: set[int]) -> int:
    """Javadoc blocks the write adds to, above a member that is not private: one line each."""
    lines = after.splitlines()
    exempt = 0
    for first, last in javadoc:
        if not added.intersection(range(first, last + 1)):
            continue
        following = next((n for n in range(last + 1, len(lines) + 1) if n in code), None)
        while following is not None and lines[following - 1].lstrip().startswith("@"):
            following = next((n for n in range(following + 1, len(lines) + 1) if n in code), None)
        if following is not None and not lines[following - 1].lstrip().startswith("private "):
            exempt += 1
    return exempt


def change(before: str, after: str) -> Change:
    """Lines added and removed by the write, and how many of the added lines are comment and how many code."""
    old, new = before.splitlines(), after.splitlines()
    added: list[int] = []
    removed = 0
    for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, old, new, autojunk=False).get_opcodes():
        if tag in {"replace", "delete"}:
            removed += i2 - i1
        if tag in {"replace", "insert"}:
            added.extend(range(j1 + 1, j2 + 1))
    kinds = line_kinds(after)
    comments = [n for n in added if n in kinds["comment"]]
    code = [n for n in added if n in kinds["code"]]
    return {
        "addedLines": len(added),
        "removedLines": removed,
        "addedCodeLines": len(code),
        "addedCommentLines": len(comments),
        "addedComments": comments,
        "exemptJavadocLines": exempt_javadoc(after, set(added), kinds["javadoc"], kinds["code"]),
    }


def comment_share_reason(found: Change, policy: CommentPolicy) -> str | None:
    """Why the write adds too many comment lines, or None: the share applies to writes that add
    ``comments.minAddedLines`` or more and counts one Javadoc line per non-private member out when allowed."""
    if found["addedLines"] < policy["minAddedLines"]:
        return None
    exempt = found["exemptJavadocLines"] if policy["exemptPublicJavadoc"] else 0
    counted = max(0, found["addedCommentLines"] - exempt)
    share = counted / found["addedLines"]
    if share <= policy["maxAddedShare"]:
        return None
    shown = ", ".join(str(n) for n in found["addedComments"][:SHOWN])
    more = "..." if len(found["addedComments"]) > SHOWN else ""
    return (
        f"{counted} of the {found['addedLines']} added lines are comments ({share:.0%}, at most "
        f"{policy['maxAddedShare']:.0%}, comments.maxAddedShare) at lines {shown}{more}: say why in one line, "
        "or nothing"
    )


def introduced(candidate: Sequence[Mapping[str, Any]], current: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    """The candidate's findings beyond those the current text already has (by code and message)."""
    left = Counter((str(row.get("code")), str(row.get("message"))) for row in current)
    new: list[dict[str, Any]] = []
    for row in candidate:
        key = (str(row.get("code")), str(row.get("message")))
        if left[key] > 0:
            left[key] -= 1
        else:
            new.append(dict(row))
    return sorted(new, key=lambda r: (int(r.get("level", 3)), int(r.get("line", 0))))


def evaluate(
    candidate: Sequence[Mapping[str, Any]],
    current: Sequence[Mapping[str, Any]],
    *,
    syntax: Sequence[Mapping[str, Any]],
    mode: str,
    before: str,
    after: str,
    comments: CommentPolicy | None = None,
) -> Verdict:
    """The verdict on a write that turns ``before`` into ``after``; with ``comments``, a write whose added lines are
    too much comment counts as a blocking reason too."""
    found = introduced(candidate, current)
    reasons = [f"line {e.get('line')}:{e.get('column')} does not parse: {e.get('message')}" for e in syntax]
    errors = [row for row in found if int(row.get("level", 3)) <= ERROR_LEVEL]
    reasons += [f"line {r.get('line')}: [{r.get('source')}/{r.get('code')}] {r.get('message')}" for r in errors]
    changed = change(before, after)
    share = comment_share_reason(changed, comments) if comments else None
    if share:
        reasons.append(share)
    decision: Decision = "pass"
    if reasons:
        decision = "block" if mode == "block" else "warn"
    return {
        "decision": decision,
        "reasons": reasons,
        "introduced": found,
        "existing": max(0, len(candidate) - len(found)),
        "change": changed,
        "advice": [],
    }


def rows(items: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    """LSP diagnostics as the rows the gate compares: 1-based line and column, level, source, code, message."""
    out = []
    for item in items:
        start = (item.get("range") or {}).get("start") or {}
        out.append(
            {
                "line": int(start.get("line", 0)) + 1,
                "column": int(start.get("character", 0)) + 1,
                "level": int(item.get("severity", 3)),
                "source": item.get("source", ""),
                "code": item.get("code", ""),
                "message": item.get("message", ""),
            }
        )
    return out


def check_candidate(
    call: EngineCall,
    root: Path,
    path: Path,
    *,
    before: str,
    after: str,
    mode: str,
    comments: CommentPolicy | None = None,
    reuse: Mapping[str, Any] | None = None,
) -> Verdict:
    """The verdict on writing ``after`` to ``path``: the engine previews the candidate (nothing is written) and
    checks the current file, so only what the write introduces counts. Engine errors propagate."""
    return check_candidates(call, root, [(path, before, after)], mode=mode, comments=comments, reuse=reuse)[0]


def check_candidates(
    call: EngineCall,
    root: Path,
    candidates: Sequence[tuple[Path, str, str]],
    *,
    mode: str,
    comments: CommentPolicy | None = None,
    reuse: Mapping[str, Any] | None = None,
) -> list[Verdict]:
    """The verdicts on writing several files at once (``(path, before, after)`` each), previewed together, so a
    change that spans files (a signature and its call sites) is compiled as one."""
    texts = [{"uri": path.as_uri(), "text": after} for path, _before, after in candidates]
    preview = call("groovyscope/preview", {"root": str(root), "texts": texts, "include": INCLUDE})
    existing = [path.as_uri() for path, _before, _after in candidates if path.is_file()]
    current: Mapping[str, Any] = {}
    if existing:
        checked = call("groovyscope/check", {"root": str(root), "uris": existing, "include": INCLUDE})
        current = checked.get("diagnostics") or {}
    verdicts: list[Verdict] = []
    for path, before, after in candidates:
        uri = path.as_uri()
        found = (preview.get("results") or {}).get(uri) or {}
        verdict = evaluate(
            rows(found.get("diagnostics") or []),
            rows(current.get(uri) or []),
            syntax=found.get("syntax") or [],
            mode=mode,
            before=before,
            after=after,
            comments=comments,
        )
        if reuse is not None and not found.get("syntax"):
            blocking, advice = mcp_reuse.write_findings(call, root, path, before=before, after=after, settings=reuse)
            reasons = [*verdict["reasons"], *blocking]
            decision: Decision = ("block" if mode == "block" else "warn") if reasons else "pass"
            verdict = {**verdict, "reasons": reasons, "decision": decision, "advice": advice}
        verdicts.append(verdict)
    return verdicts


def render(verdict: Verdict, shown: str, *, planned: bool = False) -> str:
    """The block the agent reads: the decision and its reasons first, then the other findings the write adds."""
    what = "planned write" if planned else "write"
    head = {
        "block": f"groovyscope gate: {what} to {shown} BLOCKED",
        "warn": f"groovyscope gate: {what} to {shown} would be blocked in block mode",
        "pass": f"groovyscope gate: {what} to {shown} passes",
    }[verdict["decision"]]
    lines = [head]
    lines += [f"  {reason}" for reason in verdict["reasons"][:SHOWN]]
    others = [row for row in verdict["introduced"] if int(row.get("level", 3)) > ERROR_LEVEL]
    lines += [f"  line {r.get('line')}: [{r.get('code')}] {r.get('message')}" for r in others[:SHOWN]]
    lines += [f"  {item}" for item in verdict.get("advice", [])[:SHOWN]]
    hidden = len(verdict["reasons"]) + len(others) - min(SHOWN, len(verdict["reasons"])) - min(SHOWN, len(others))
    if hidden > 0:
        lines.append(f"  ... {hidden} more")
    if verdict["decision"] == "block":
        lines.append("  fix the text and write again; groovyscope.edit_symbol gives a gated replacement")
    return "\n".join(lines) if len(lines) > 1 or verdict["decision"] != "pass" else ""


def policy_summary(effective: Mapping[str, Any]) -> str:
    """The rules of the edit policy, one line each, built from the configuration so the numbers are real."""
    gate = settings(effective)
    lines = ["groovyscope edit policy for Groovy, Gradle and Jenkinsfile files:"]
    lines.append(
        "- edit through groovyscope.edit_symbol (its dry run gives the exact write arguments and the gate's verdict)"
    )
    if gate["enabled"]:
        lines.append(
            f"- every write is gated before it lands ({gate['mode']} mode): new syntax and compile errors "
            + ("block it" if gate["mode"] == "block" else "are reported")
        )
    policy = comment_policy(effective)
    lines.append(
        f"- comments say why, in one line; at most {policy['maxAddedShare']:.0%} of a write's added lines may be "
        "comments"
    )
    if gate["requirePlan"]:
        lines.append(f"- writes to Groovy files over {gate['requirePlanAboveLines']} lines need an edit_symbol plan")
    return "\n".join(lines)
