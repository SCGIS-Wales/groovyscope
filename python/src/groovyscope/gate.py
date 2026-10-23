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
from groovyscope.source_lines import line_kinds

Decision = Literal["block", "warn", "pass"]
ERROR_LEVEL = 1
SHOWN = 8  # findings named in a verdict; the rest are counted
INCLUDE = ["compile", "lint", "practices"]
EngineCall = Callable[[str, dict[str, Any]], Mapping[str, Any]]


class GateSettings(TypedDict):
    enabled: bool
    mode: str  # block | warn
    trustPlans: bool
    requirePlan: bool
    requirePlanAboveLines: int


class Change(TypedDict):
    addedLines: int
    removedLines: int
    addedCodeLines: int
    addedCommentLines: int
    addedComments: list[int]  # 1-based lines of the candidate


class Verdict(TypedDict):
    decision: Decision
    reasons: list[str]
    introduced: list[dict[str, Any]]
    existing: int
    change: Change


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
    }


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
) -> Verdict:
    """The verdict on a write that turns ``before`` into ``after``."""
    found = introduced(candidate, current)
    reasons = [f"line {e.get('line')}:{e.get('column')} does not parse: {e.get('message')}" for e in syntax]
    errors = [row for row in found if int(row.get("level", 3)) <= ERROR_LEVEL]
    reasons += [f"line {r.get('line')}: [{r.get('source')}/{r.get('code')}] {r.get('message')}" for r in errors]
    decision: Decision = "pass"
    if reasons:
        decision = "block" if mode == "block" else "warn"
    return {
        "decision": decision,
        "reasons": reasons,
        "introduced": found,
        "existing": max(0, len(candidate) - len(found)),
        "change": change(before, after),
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


def check_candidate(call: EngineCall, root: Path, path: Path, *, before: str, after: str, mode: str) -> Verdict:
    """The verdict on writing ``after`` to ``path``: the engine previews the candidate (nothing is written) and
    checks the current file, so only what the write introduces counts. Engine errors propagate."""
    uri = path.as_uri()
    preview = call("groovyscope/preview", {"root": str(root), "texts": [{"uri": uri, "text": after}]})
    current: Sequence[Mapping[str, Any]] = []
    if path.is_file():
        checked = call("groovyscope/check", {"root": str(root), "uris": [uri], "include": INCLUDE})
        current = (checked.get("diagnostics") or {}).get(uri) or []
    found = (preview.get("results") or {}).get(uri) or {}
    return evaluate(
        rows(found.get("diagnostics") or []),
        rows(current),
        syntax=found.get("syntax") or [],
        mode=mode,
        before=before,
        after=after,
    )


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
    if gate["requirePlan"]:
        lines.append(f"- writes to Groovy files over {gate['requirePlanAboveLines']} lines need an edit_symbol plan")
    return "\n".join(lines)
