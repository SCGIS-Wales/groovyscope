"""Reuse before writing: suggest_reuse, the gate's GS080 to GS082 and review_changes' duplicatesAdded."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from groovyscope import gate, mcp_reuse, mcp_server, mcp_understanding

BEFORE = "class A {\n    int a() { 1 }\n}\n"
BLOCK = "".join(f"    int v{n} = {n}\n" for n in range(6))
AFTER = "class A {\n    int a() { 1 }\n" + BLOCK + "}\n"


def match(kind: str, root: Path) -> dict[str, Any]:
    return {
        "symbol": "demo.Strings.slug",
        "uri": (root / "src/Strings.groovy").as_uri(),
        "line": 6,
        "endLine": 9,
        "kind": kind,
        "score": 1.0,
        "reason": "same statements",
    }


def engine(root: Path, kinds: list[str], seen: list[tuple[str, dict[str, Any]]]) -> Any:
    def call(method: str, params: dict[str, Any]) -> dict[str, Any]:
        seen.append((method, params))
        if method == "groovyscope/similar":
            if "ranges" in params:
                return {"byRange": [{"range": params["ranges"][0], "matches": [match(k, root) for k in kinds]}]}
            return {"matches": [match(k, root) for k in kinds], "symbol": params.get("symbol")}
        if method == "groovyscope/preview":
            return {"results": {}}
        return {"diagnostics": {}}

    return call


def test_pure_helpers() -> None:
    assert mcp_reuse.added_blocks(BEFORE, AFTER, 6) == [(3, 8, BLOCK.rstrip("\n"))]
    assert mcp_reuse.added_blocks(BEFORE, AFTER, 7) == []
    text = "private String slugOf(String s) {\n    return Strings.slug(s)\n}\nprivate int sum(a) { a + 1 }"
    assert mcp_reuse.delegating_helpers(text) == [("slugOf", "Strings.slug")]
    assert mcp_reuse.min_lines({}) == 6 and mcp_reuse.min_lines({"reuse": {"minLines": 4}}) == 4
    assert mcp_reuse.min_lines({"duplicates": {"minLines": 8}}) == 8


def test_the_gate_holds_back_a_repeated_block_and_advises_on_lookalikes(tmp_path: Path) -> None:
    target = tmp_path / "A.groovy"
    target.write_text(BEFORE, encoding="utf-8")
    seen: list[tuple[str, dict[str, Any]]] = []
    settings = gate.reuse_settings({})
    assert settings is not None and gate.reuse_settings({"reuse": {"checkWrites": False}}) is None
    verdict = gate.check_candidate(
        engine(tmp_path, ["exact"], seen), tmp_path, target, before=BEFORE, after=AFTER, mode="block", reuse=settings
    )
    assert verdict["decision"] == "block"
    assert verdict["reasons"] == [
        "lines 3-8 [GS080] repeat demo.Strings.slug (src/Strings.groovy:6): call it, or extract a shared method with "
        "extract_method"
    ]
    similar = [params for method, params in seen if method == "groovyscope/similar"]
    assert similar[0]["text"] == BLOCK.rstrip("\n") and similar[0]["line"] == 3
    advised = gate.check_candidate(
        engine(tmp_path, ["signature"], []), tmp_path, target, before=BEFORE, after=AFTER, mode="warn", reuse=settings
    )
    assert advised["decision"] == "pass" and advised["advice"][0].startswith("lines 3-8 [GS081] a method like")
    assert "[GS081]" in gate.render(advised, "A.groovy")
    plain = gate.check_candidate(
        engine(tmp_path, ["exact"], []), tmp_path, target, before=BEFORE, after=AFTER, mode="block"
    )
    assert plain["decision"] == "pass" and plain["advice"] == []

    def broken(method: str, params: dict[str, Any]) -> dict[str, Any]:
        if method == "groovyscope/similar":
            raise RuntimeError("does not parse")
        return engine(tmp_path, [], [])(method, params)

    quiet = gate.check_candidate(broken, tmp_path, target, before=BEFORE, after=AFTER, mode="block", reuse=settings)
    assert quiet["decision"] == "pass"


def test_suggest_reuse_forms(tmp_path: Path) -> None:
    seen: list[tuple[str, dict[str, Any]]] = []
    tool = mcp_reuse.reuse_tools(tmp_path, engine(tmp_path, ["similar"], seen), 5, {})["suggest_reuse"]
    found = tool(text="x()", path="src/A.groovy", line=3, paths=["src"], limit=50)
    assert found["count"] == 1 and found["matches"][0]["path"] == "src/Strings.groovy"
    assert seen[-1][1]["limit"] == 5 and seen[-1][1]["paths"] == ["src"] and seen[-1][1]["minLines"] == 6
    by_symbol = tool(symbol="A.a", path="src/A.groovy")
    assert by_symbol["symbol"] == "A.a" and seen[-1][1]["symbol"] == "A.a"
    assert tool() == {"error": "give TEXT, or SYMBOL with its PATH"}
    none = mcp_reuse.reuse_tools(tmp_path, engine(tmp_path, [], []), 5, {})["suggest_reuse"](text="y()")
    assert none == {"count": 0, "matches": [], "note": "nothing similar: write it"}


def test_duplicates_added_in_review(tmp_path: Path) -> None:
    files = [{"path": "src/A.groovy", "ranges": [[1, 2], [3, 9]]}, {"path": "src/B.groovy", "ranges": [[1, 1]]}]
    seen: list[tuple[str, dict[str, Any]]] = []
    found = mcp_reuse.duplicates_added(tmp_path, engine(tmp_path, ["exact", "calls"], seen), files, 6, 5)
    assert found == [
        {
            "path": "src/A.groovy",
            "lines": [3, 9],
            "repeats": mcp_reuse.compact_match(match("exact", tmp_path), tmp_path),
        }
    ]
    assert [params["ranges"] for _m, params in seen] == [[[3, 9]]]
    assert mcp_reuse.duplicates_added(tmp_path, engine(tmp_path, ["calls"], []), files, 6, 5) == []
    review = mcp_server.reuse_review(
        tmp_path, engine(tmp_path, ["exact"], []), {"reuse": {"blockReview": True}}, files, "approve: x", 5
    )
    assert review["verdict"] == "request changes: 1 added block(s) repeat existing code"
    kept = mcp_server.reuse_review(tmp_path, engine(tmp_path, ["exact"], []), {}, files, "approve: x", 5)
    assert "verdict" not in kept and len(kept["duplicatesAdded"]) == 1
    assert mcp_server.reuse_review(tmp_path, engine(tmp_path, [], []), {}, files, "approve", 5) == {}


def test_explain_lists_helpers(tmp_path: Path) -> None:
    helpers = [
        {
            "symbol": "demo.Strings.slug",
            "signature": "String slug(String)",
            "line": 5,
            "uri": (tmp_path / "S.groovy").as_uri(),
        }
    ]
    assert mcp_understanding.compact_helpers(helpers, tmp_path) == [
        {"symbol": "demo.Strings.slug", "signature": "String slug(String)", "at": "S.groovy:6"}
    ]
    method = mcp_understanding.compact_explanation({"kind": "method", "name": "m", "helpers": helpers}, tmp_path)
    assert method["helpers"][0]["at"] == "S.groovy:6"
