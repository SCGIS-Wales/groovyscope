"""change_signature, extract_method and move_member: gated writes per file, planned for Kiro or applied here."""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest

from groovyscope import interaction, mcp_refactor, plans

A = "class A {\n    int total(int a) { a }\n}\n"
B = "class B {\n    int x() { new A().total(1) }\n}\n"
ERROR = {
    "range": {"start": {"line": 1, "character": 0}},
    "severity": 1,
    "source": "groovyc",
    "code": "e",
    "message": "m",
}


@pytest.fixture
def workspace(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    monkeypatch.setenv("GROOVYSCOPE_CACHE_DIR", str(tmp_path / "cache"))
    (tmp_path / "A.groovy").write_text(A, encoding="utf-8")
    (tmp_path / "B.groovy").write_text(B, encoding="utf-8")
    return tmp_path


def engine(
    root: Path, answer: dict[str, Any], *, preview: list[dict[str, Any]] | None = None, similar: list[Any] | None = None
) -> Any:
    seen: list[tuple[str, dict[str, Any]]] = []

    def call(method: str, params: dict[str, Any]) -> dict[str, Any]:
        seen.append((method, params))
        if method.startswith("groovyscope/") and method.split("/")[1] in {
            "changeSignature",
            "extractMethod",
            "moveMember",
        }:
            return answer
        if method == "groovyscope/preview":
            uri = params["texts"][0]["uri"]
            return {"results": {uri: {"syntax": [], "diagnostics": preview or []}}}
        if method == "groovyscope/similar":
            return {"matches": similar or []}
        return {"diagnostics": {}}

    call.seen = seen  # type: ignore[attr-defined]
    return call


def signature_answer(root: Path) -> dict[str, Any]:
    return {
        "files": [
            {
                "uri": (root / "A.groovy").as_uri(),
                "oldStr": "int total(int a)",
                "newStr": "int total(int a, int b = 0)",
            },
            {"uri": (root / "B.groovy").as_uri(), "oldStr": "total(1)", "newStr": "total(1, 0)"},
        ],
        "callSites": 1,
        "declaration": "int total(int a, int b = 0)",
    }


def tools(root: Path, call: Any) -> dict[str, Any]:
    return mcp_refactor.refactor_tools(root, call, 5, {}, clock=lambda: 1000.0)


def test_a_dry_run_plans_one_gated_write_per_file(workspace: Path) -> None:
    call = engine(workspace, signature_answer(workspace))
    params = [{"name": "a", "type": "int"}, {"name": "b", "type": "int", "default": "0"}]
    result = tools(workspace, call)["change_signature"]("A.groovy", "A.total", params, return_type="int", line=2)
    sent = call.seen[0][1]
    assert sent["parameters"] == params and sent["returnType"] == "int" and sent["line"] == 2
    assert result["callSites"] == 1 and result["declaration"] == "int total(int a, int b = 0)"
    assert [f["path"] for f in result["files"]] == ["A.groovy", "B.groovy"]
    assert (
        all(f["gate"]["decision"] == "pass" for f in result["files"])
        and "+    int x() { new A().total(1, 0) }" in result["files"][1]["diff"]
    )
    assert result["applyWith"].startswith("one write per file")
    store = plans.store_for(workspace, {"GROOVYSCOPE_CACHE_DIR": str(workspace / "cache")})
    assert plans.matching(
        store, workspace / "B.groovy", B, "total(1)", "total(1, 0)", clock=lambda: 1000.0, ttl_minutes=120
    )
    assert (workspace / "A.groovy").read_text(encoding="utf-8") == A


def test_refusals_stale_files_and_blocked_files(workspace: Path) -> None:
    refused = tools(workspace, engine(workspace, {"refused": "2 call site(s) are matched by name only"}))
    assert refused["move_member"]("A.groovy", "A.total", "B") == {
        "applied": False,
        "refused": "2 call site(s) are matched by name only",
    }
    stale = signature_answer(workspace)
    stale["files"][1]["oldStr"] = "gone"
    answer = tools(workspace, engine(workspace, stale))["change_signature"]("A.groovy", "A.total", [])
    assert answer == {"applied": False, "reason": "B.groovy changed since the engine read it; ask again"}
    blocked = tools(workspace, engine(workspace, signature_answer(workspace), preview=[ERROR]))["change_signature"]
    dry = blocked("A.groovy", "A.total", [])
    assert dry["applyWith"].startswith("do not apply") and dry["files"][0]["gate"]["decision"] == "block"
    applied = blocked("A.groovy", "A.total", [], apply=True)
    assert applied["applied"] is False and "gate blocks" in applied["reason"]
    assert (workspace / "B.groovy").read_text(encoding="utf-8") == B


def test_apply_asks_writes_and_verifies(workspace: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    asked: list[str] = []
    monkeypatch.setattr(interaction, "confirm", lambda question, **_k: asked.append(question) or interaction.DECLINED)
    declined = tools(workspace, engine(workspace, signature_answer(workspace)))["change_signature"](
        "A.groovy", "A.total", [], apply=True
    )
    assert declined["applied"] is False and declined["confirmation"] == "declined"
    assert asked[0].startswith("Change the signature of A.total?") and "- B.groovy: 1 line(s)" in asked[0]
    monkeypatch.setattr(interaction, "confirm", lambda *_a, **_k: interaction.ACCEPTED)
    done = tools(workspace, engine(workspace, signature_answer(workspace)))["change_signature"](
        "A.groovy", "A.total", [], apply=True
    )
    assert done["applied"] is True and done["confirmation"] == "accepted" and "verified" in done
    assert "total(1, 0)" in (workspace / "B.groovy").read_text(encoding="utf-8")


def test_extract_method_says_when_existing_code_does_it(workspace: Path) -> None:
    answer = {
        "files": [{"uri": (workspace / "A.groovy").as_uri(), "oldStr": "{ a }", "newStr": "{ twice(a) }"}],
        "method": "private static int twice(int a)",
        "parameters": [{"name": "a", "type": "int"}],
        "returns": None,
        "static": True,
    }
    match = {
        "symbol": "U.twice",
        "uri": (workspace / "U.groovy").as_uri(),
        "line": 3,
        "endLine": 5,
        "kind": "exact",
        "score": 1.0,
    }
    call = engine(workspace, answer, similar=[match])
    result = tools(workspace, call)["extract_method"]("A.groovy", 2, 2, "twice")
    assert result["method"] == "private static int twice(int a)" and result["static"] is True
    assert result["existing"][0]["symbol"] == "U.twice" and "call it instead" in result["note"]
    similar = [params for method, params in call.seen if method == "groovyscope/similar"]
    assert similar[0]["text"] == "    int total(int a) { a }"
    quiet = tools(workspace, engine(workspace, answer))["extract_method"]("A.groovy", 2, 2, "twice")
    assert "existing" not in quiet


def test_class_design_compacts_the_engine_answer(workspace: Path) -> None:
    item = {
        "class": "demo.A",
        "uri": (workspace / "A.groovy").as_uri(),
        "path": "A.groovy",
        "cohesion": {"lcom4": 2, "groups": [{"methods": ["a"]}, {"methods": ["b"]}]},
        "featureEnvy": [],
        "suggestions": [{"kind": "split", "message": "two jobs", "tool": "extract_method(...)"}] * 9,
        "dataOwner": None,
    }
    seen: list[dict[str, Any]] = []

    def call(_method: str, params: dict[str, Any]) -> dict[str, Any]:
        seen.append(params)
        return {"found": True, "items": [item]}

    design = mcp_refactor.refactor_tools(workspace, call, 5, {})["class_design"]
    answer = design(symbol="demo.A")
    (found,) = answer["items"]
    assert answer["found"] is True and seen[0]["symbol"] == "demo.A"
    assert "uri" not in found and "featureEnvy" not in found and "dataOwner" not in found
    assert found["cohesion"]["lcom4"] == 2 and len(found["suggestions"]) == 5
    design(path="A.groovy")
    assert seen[-1]["uri"] == (workspace / "A.groovy").as_uri()
    assert design() == {"error": "give a symbol (Class) or a path"}
