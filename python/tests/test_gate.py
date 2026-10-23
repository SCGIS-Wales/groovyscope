"""The edit gate's verdict: only introduced findings count, block or warn by mode, and a readable block."""

from __future__ import annotations

from typing import Any

from groovyscope import gate

BEFORE = "class A {\n    int a() { 1 }\n}\n"
AFTER = "class A {\n    // one\n    int a() { 1 }\n    int b() { 2 }\n}\n"


def row(code: str, message: str, level: int = 1, line: int = 1) -> dict[str, Any]:
    return {"code": code, "message": message, "level": level, "line": line, "source": "groovyc"}


def test_settings_fill_the_defaults_and_take_the_configuration() -> None:
    assert gate.settings({}) == {
        "enabled": True,
        "mode": "warn",
        "trustPlans": True,
        "requirePlan": False,
        "requirePlanAboveLines": 40,
    }
    assert gate.settings({"hooks": {"preWrite": {"mode": "block"}}})["mode"] == "block"


def test_change_counts_added_code_and_comment_lines() -> None:
    assert gate.change(BEFORE, AFTER) == {
        "addedLines": 2,
        "removedLines": 0,
        "addedCodeLines": 1,
        "addedCommentLines": 1,
        "addedComments": [2],
    }
    assert gate.change(AFTER, BEFORE)["removedLines"] == 2


def test_only_introduced_findings_count() -> None:
    current = [row("error", "x"), row("error", "x")]
    candidate = [row("error", "x"), row("error", "x"), row("error", "x", line=9), row("GS001", "y", level=3)]
    found = gate.introduced(candidate, current)
    assert [(r["code"], r["line"]) for r in found] == [("error", 9), ("GS001", 1)]
    assert gate.introduced(current, candidate) == []


def test_evaluate_blocks_or_warns_by_mode_and_passes_old_errors() -> None:
    syntax = [{"line": 2, "column": 5, "message": "unexpected token"}]
    blocked = gate.evaluate([], [], syntax=syntax, mode="block", before=BEFORE, after=AFTER)
    assert blocked["decision"] == "block" and blocked["reasons"] == ["line 2:5 does not parse: unexpected token"]
    warned = gate.evaluate([row("error", "no such method")], [], syntax=[], mode="warn", before=BEFORE, after=AFTER)
    assert warned["decision"] == "warn" and "[groovyc/error] no such method" in warned["reasons"][0]
    kept = gate.evaluate([row("error", "old")], [row("error", "old")], syntax=[], mode="block", before="", after="")
    assert kept["decision"] == "pass" and kept["existing"] == 1
    info = gate.evaluate([row("GS001", "style", level=3)], [], syntax=[], mode="block", before="", after="")
    assert info["decision"] == "pass" and len(info["introduced"]) == 1


def test_render_puts_the_decision_first_and_caps_the_list() -> None:
    errors = [row("error", f"e{n}", line=n) for n in range(10)]
    verdict = gate.evaluate(errors, [], syntax=[], mode="block", before="", after="")
    text = gate.render(verdict, "src/A.groovy")
    assert text.splitlines()[0] == "groovyscope gate: write to src/A.groovy BLOCKED"
    assert "  ... 2 more" in text and "edit_symbol" in text.splitlines()[-1]
    warned = gate.render(gate.evaluate(errors[:1], [], syntax=[], mode="warn", before="", after=""), "A", planned=True)
    assert warned.startswith("groovyscope gate: planned write to A would be blocked in block mode")
    clean = gate.evaluate([], [], syntax=[], mode="block", before="", after="")
    assert gate.render(clean, "A") == ""
    style = gate.evaluate([row("GS001", "style", level=3)], [], syntax=[], mode="block", before="", after="")
    assert gate.render(style, "A") == "groovyscope gate: write to A passes\n  line 1: [GS001] style"


def test_policy_summary_follows_the_configuration() -> None:
    assert "are reported" in gate.policy_summary({})
    strict = {"hooks": {"preWrite": {"mode": "block", "requirePlan": True, "requirePlanAboveLines": 10}}}
    summary = gate.policy_summary(strict)
    assert "block it" in summary and "over 10 lines need an edit_symbol plan" in summary
    assert "gated" not in gate.policy_summary({"hooks": {"preWrite": {"enabled": False}}})
