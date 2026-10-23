"""edit_symbol: a planned, gated edit of one declaration, applied by Kiro's write or, with APPLY, here."""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest

from groovyscope import interaction, mcp_edit, plans

BEFORE = "class A {\n    int a() { 1 }\n}\n"
AFTER = "class A {\n    int a() { 2 }\n}\n"
ERROR = {
    "range": {"start": {"line": 1, "character": 4}},
    "severity": 1,
    "source": "groovyc",
    "code": "e",
    "message": "m",
}


def engine(target: Path, *, after: str = AFTER, preview: list[dict[str, Any]] | None = None) -> Any:
    calls: list[tuple[str, dict[str, Any]]] = []
    uri = target.as_uri()

    def call(method: str, params: dict[str, Any]) -> dict[str, Any]:
        calls.append((method, params))
        if method == "groovyscope/edit":
            return {"text": after, "oldStr": "{ 1 }", "newStr": "{ 2 }", "startLine": 2, "endLine": 2, "symbol": "A.a"}
        if method == "groovyscope/preview":
            return {"results": {uri: {"syntax": [], "diagnostics": preview or []}}}
        return {"diagnostics": {uri: []}}

    call.calls = calls  # type: ignore[attr-defined]
    return call


@pytest.fixture
def target(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    monkeypatch.setenv("GROOVYSCOPE_CACHE_DIR", str(tmp_path / "cache"))
    path = tmp_path / "A.groovy"
    path.write_text(BEFORE, encoding="utf-8")
    return path


def tools(root: Path, call: Any, settings: dict[str, Any] | None = None) -> Any:
    return mcp_edit.edit_tools(root, call, settings or {}, clock=lambda: 1000.0)["edit_symbol"]


def test_a_dry_run_plans_the_write_and_stores_the_plan(target: Path) -> None:
    call = engine(target)
    result = tools(target.parent, call)("A.groovy", "a", "replace_body", text="2", line=2)
    assert call.calls[0] == (
        "groovyscope/edit",
        {
            "root": str(target.parent),
            "uri": target.as_uri(),
            "symbol": "a",
            "operation": "replace_body",
            "text": "2",
            "line": 2,
        },
    )
    assert [method for method, _ in call.calls] == ["groovyscope/edit", "groovyscope/preview", "groovyscope/check"]
    assert result["gate"]["decision"] == "pass" and result["lines"] == [2, 2] and result["symbol"] == "A.a"
    assert "-    int a() { 1 }\n+    int a() { 2 }" in result["diff"]
    assert result["plan"]["old_str"] == "{ 1 }" and result["applyWith"].startswith("write command str_replace")
    stored = plans.matching(
        plans.store_for(target.parent, {"GROOVYSCOPE_CACHE_DIR": str(target.parent / "cache")}),
        target,
        BEFORE,
        "{ 1 }",
        "{ 2 }",
        clock=lambda: 1000.0,
        ttl_minutes=120,
    )
    assert stored is not None and stored["plan_id"] == result["plan"]["plan_id"]
    assert target.read_text(encoding="utf-8") == BEFORE  # a dry run writes nothing


def test_a_blocked_edit_is_not_offered_and_not_applied(target: Path) -> None:
    call = engine(target, preview=[ERROR])
    strict = {"hooks": {"preWrite": {"mode": "block"}}}
    dry = tools(target.parent, call, strict)("A.groovy", "a", "replace_body", text="2")
    assert dry["gate"]["decision"] == "block" and dry["applyWith"].startswith("do not apply")
    lenient = tools(target.parent, call)("A.groovy", "a", "replace_body", text="2")
    assert lenient["gate"]["decision"] == "block" and lenient["applyWith"].startswith("write command")
    applied = tools(target.parent, call)("A.groovy", "a", "replace_body", text="2", apply=True)
    assert applied["edited"] is False and "gate blocks" in applied["reason"]
    assert target.read_text(encoding="utf-8") == BEFORE


def test_apply_writes_verifies_and_respects_a_no(target: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    call = engine(target)
    done = tools(target.parent, call)("A.groovy", "a", "replace_body", text="2", apply=True)
    assert done["edited"] is True and "verified" in done and "confirmation" not in done
    assert target.read_text(encoding="utf-8") == AFTER

    target.write_text(BEFORE, encoding="utf-8")
    asked: list[str] = []
    monkeypatch.setattr(interaction, "confirm", lambda question, **_k: asked.append(question) or interaction.DECLINED)
    declined = tools(target.parent, call, {"edits": {"diffLinesInQuestion": 3}})(
        "A.groovy", "a", "replace_body", text="2", apply=True
    )
    assert declined["edited"] is False and declined["confirmation"] == "declined"
    assert asked[0].splitlines()[0] == "Edit A.a in A.groovy (replace_body)?" and len(asked[0].splitlines()) == 4
    assert target.read_text(encoding="utf-8") == BEFORE

    monkeypatch.setattr(interaction, "confirm", lambda *_a, **_k: interaction.ACCEPTED)
    accepted = tools(target.parent, call)("A.groovy", "a", "replace_body", text="2", apply=True)
    assert accepted["edited"] is True and accepted["confirmation"] == "accepted"


def test_apply_rolls_back_new_compile_errors_and_unknown_operations_are_refused(target: Path) -> None:
    call = engine(target)
    checks = iter([{"diagnostics": {}}, {"diagnostics": {}}, {"diagnostics": {target.as_uri(): [ERROR]}}])

    def broken(method: str, params: dict[str, Any]) -> dict[str, Any]:
        return next(checks) if method == "groovyscope/check" else call(method, params)

    rolled = tools(target.parent, broken)("A.groovy", "a", "replace_body", text="2", apply=True)
    assert rolled["edited"] is False and rolled["rolledBack"] is True and "the edit left 1 new" in rolled["reason"]
    assert target.read_text(encoding="utf-8") == BEFORE
    refused = tools(target.parent, call)("A.groovy", "a", "rewrite")
    assert (
        refused == {"edited": False, "reason": refused["reason"]} and "unknown operation rewrite" in refused["reason"]
    )


def test_an_edit_that_leaves_types_undeclared_is_held_back(target: Path) -> None:
    call = engine(target)

    def untyped(method: str, params: dict[str, Any]) -> dict[str, Any]:
        answer = call(method, params)
        if method == "groovyscope/edit":
            return {**answer, "untyped": [
                {"line": 2, "what": "parameter x", "inferred": "String"},
                {"line": 2, "what": "return type of a", "inferred": None}]}  # fmt: skip
        return answer

    dry = tools(target.parent, untyped)("A.groovy", "a", "replace", text="def a(x) { x }")
    assert dry["gate"]["decision"] == "block"
    assert dry["gate"]["reasons"] == [
        "line 2: parameter x has no declared type (it is String): declare it",
        "line 2: return type of a has no declared type: declare it",
    ]
    applied = tools(target.parent, untyped)("A.groovy", "a", "replace", text="def a(x) { x }", apply=True)
    assert applied["edited"] is False and target.read_text(encoding="utf-8") == BEFORE
