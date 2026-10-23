"""Symbol edits for the agent. groovyscope plans a change to one declaration (the engine's ``groovyscope/edit``),
gates it on a preview of the result, and hands back the exact ``str_replace`` for Kiro's own ``write``, so the user
still sees Kiro's diff and answers its prompt; the plan is stored so the write hooks recognise it. With APPLY the
edit is written here instead, after asking, recompiled, and rolled back on new compile errors.
"""

from __future__ import annotations

import difflib
import os
import time
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any, TypedDict

from groovyscope import config, gate, interaction, plans
from groovyscope.edits import FileEdit, apply_verified, read_document
from groovyscope.paths import Call, relative_path, to_uri, uri_to_path

OPERATIONS = ("replace", "replace_body", "insert_before", "insert_after", "delete", "add_member", "add_import")
DIFF_CONTEXT = 2
Clock = Callable[[], float]


class EditSettings(TypedDict):
    plansKept: int
    planTtlMinutes: float
    diffLinesInQuestion: int


def edit_settings(settings: Mapping[str, Any]) -> EditSettings:
    """``edits`` with the defaults filled in."""
    section: Mapping[str, Any] = settings.get("edits") or {}

    def value(key: str) -> Any:
        return section.get(key, config.default("edits", key))

    return {
        "plansKept": int(value("plansKept")),
        "planTtlMinutes": float(value("planTtlMinutes")),
        "diffLinesInQuestion": int(value("diffLinesInQuestion")),
    }


def unified(before: str, after: str, shown: str) -> list[str]:
    """The edit as a unified diff, two lines of context."""
    return list(
        difflib.unified_diff(
            before.splitlines(), after.splitlines(), f"a/{shown}", f"b/{shown}", n=DIFF_CONTEXT, lineterm=""
        )
    )


def untyped_reasons(untyped: Sequence[Mapping[str, Any]]) -> list[str]:
    """The declarations an edit's text leaves without a type (``types.declareOnEdit``), with what the static type
    checker infers when it can tell."""
    return [
        f"line {item.get('line')}: {item.get('what')} has no declared type"
        + (f" (it is {item['inferred']}): declare it" if item.get("inferred") else ": declare it")
        for item in untyped
    ]


def reaching_tests(call: Call, root: Path, symbol: str, cap: int = 10) -> list[str]:
    """The tests that reach SYMBOL (``Class.member``) through the call graph, as ``Class.method``: the advice that
    names what to run, and what to update, after the edit. Empty when the engine cannot tell."""
    owner, _, member = symbol.rpartition(".")
    target = f"{owner}#{member}" if owner and member and member[:1].islower() else symbol
    try:
        reports = call("groovyscope/impact", {"root": str(root), "symbol": target, "change": "behaviour"})
    except (OSError, RuntimeError, ValueError):
        return []
    names: list[str] = []
    for report in (reports if isinstance(reports, list) else [])[:1]:
        for site in report.get("tests") or []:
            caller = site.get("caller") or {}
            name = f"{caller.get('containerName') or caller.get('container') or ''}.{caller.get('name')}".lstrip(".")
            if name not in names:
                names.append(name)
    return names[:cap]


def gate_summary(verdict: gate.Verdict) -> dict[str, Any]:
    """The verdict as the tool reports it: the decision, why, and what the edit adds."""
    summary: dict[str, Any] = {"decision": verdict["decision"]}
    if verdict["reasons"]:
        summary["reasons"] = verdict["reasons"][: gate.SHOWN]
    if verdict["advice"]:
        summary["advice"] = verdict["advice"][: gate.SHOWN]
    others = [row for row in verdict["introduced"] if int(row.get("level", 3)) > gate.ERROR_LEVEL]
    if others:
        summary["introduced"] = [f"line {r['line']}: [{r['code']}] {r['message']}" for r in others[: gate.SHOWN]]
    change = verdict["change"]
    summary["change"] = {
        "addedLines": change["addedLines"],
        "removedLines": change["removedLines"],
        "addedCommentLines": change["addedCommentLines"],
    }
    return summary


def edit_symbol(
    root: Path,
    call: Call,
    settings: Mapping[str, Any],
    confirm_policy: str,
    clock: Clock,
    path: str,
    symbol: str,
    operation: str,
    text: str | None = None,
    position: str | None = None,
    line: int | None = None,
    apply: bool = False,
) -> dict[str, Any]:
    """Plan an edit of SYMBOL (method, Class.method, Class, Class.<init>) in PATH and gate it on a preview of the
    result. OPERATION: replace (the declaration with its comments and annotations), replace_body, insert_before,
    insert_after, delete, add_member (SYMBOL a class; POSITION start, end or after:<member>) or add_import (TEXT
    a.b.C). TEXT is the new code, re-indented to fit. LINE picks an overload. A dry run returns the diff, the gate's
    verdict and the exact old_str and new_str to apply with write (str_replace); APPLY writes it here instead."""
    if operation not in OPERATIONS:
        return {"edited": False, "reason": f"unknown operation {operation}; one of {', '.join(OPERATIONS)}"}
    uri = to_uri(root, path)
    params: dict[str, Any] = {"root": str(root), "uri": uri, "symbol": symbol, "operation": operation}
    params.update({k: v for k, v in (("text", text), ("position", position), ("line", line)) if v is not None})
    raw = call("groovyscope/edit", params)
    target = uri_to_path(uri)
    before = read_document(target)
    after = str(raw["text"])
    shown = relative_path(uri, root)
    mode = gate.settings({"hooks": settings.get("hooks") or {}})["mode"]
    comments = gate.comment_policy({"comments": settings.get("comments") or {}})
    verdict = gate.check_candidate(
        call,
        root,
        target,
        before=before,
        after=after,
        mode="block",
        comments=comments,
        reuse=gate.reuse_settings(settings),
    )
    untyped = untyped_reasons(raw.get("untyped") or [])
    if untyped:  # types.declareOnEdit: the new text declares its types, or the edit is held back
        verdict = {**verdict, "reasons": [*verdict["reasons"], *untyped], "decision": "block"}
    diff = unified(before, after, shown)
    tests = reaching_tests(call, root, str(raw.get("symbol", symbol))) if operation != "add_import" else []
    result: dict[str, Any] = {
        "symbol": raw.get("symbol", symbol),
        "operation": operation,
        "lines": [raw.get("startLine"), raw.get("endLine")],
        "gate": gate_summary(verdict),
        "diff": "\n".join(diff),
    }
    if tests:
        result["tests"] = tests
    limits = edit_settings(settings)
    if not apply:
        plan = plans.make(target, before, after, old_str=str(raw["oldStr"]), new_str=str(raw["newStr"]), now=clock())
        store = plans.store_for(root, dict(os.environ))
        plans.save(store, plan, kept=limits["plansKept"], clock=clock, ttl_minutes=limits["planTtlMinutes"])
        result["plan"] = {
            "plan_id": plan["plan_id"],
            "path": shown,
            "old_str": plan["old_str"],
            "new_str": plan["new_str"],
        }
        result["applyWith"] = "write command str_replace with this path, old_str and new_str exactly as given"
        if verdict["decision"] == "block" and mode == "block":
            result["applyWith"] = "do not apply: the gate blocks this edit; fix TEXT and plan again"
        return result
    if verdict["decision"] == "block":
        return {**result, "edited": False, "reason": "the gate blocks this edit; nothing was written"}
    question = "\n".join([f"Edit {result['symbol']} in {shown} ({operation})?", *diff[: limits["diffLinesInQuestion"]]])
    decision = interaction.confirm(question, policy=confirm_policy)
    if decision in {interaction.DECLINED, interaction.CANCELLED}:
        return {**result, "edited": False, "confirmation": decision, "note": "the user said no; nothing was written"}
    changed: list[FileEdit] = [{"path": target, "before": before, "after": after}]
    outcome = apply_verified(root, call, changed, flag="edited", what="the edit")
    result.update({"edited": True, **outcome})
    if decision != interaction.UNSUPPORTED:
        result["confirmation"] = decision
    return result


def edit_tools(
    root: Path,
    call: Call,
    settings: Mapping[str, Any],
    *,
    confirm_policy: str = interaction.POLICY_AUTO,
    clock: Clock = time.time,
) -> dict[str, Callable[..., Any]]:
    """edit_symbol, bound to one workspace and engine session."""

    def bound(
        path: str,
        symbol: str,
        operation: str,
        text: str | None = None,
        position: str | None = None,
        line: int | None = None,
        apply: bool = False,
    ) -> dict[str, Any]:
        return edit_symbol(
            root, call, settings, confirm_policy, clock, path, symbol, operation, text, position, line, apply
        )

    bound.__doc__ = edit_symbol.__doc__
    return {"edit_symbol": bound}
