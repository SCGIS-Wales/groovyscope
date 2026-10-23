"""test_plan: reaching tests split by kind, repeated tests, and only the gaps as `needed`."""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import pytest

from groovyscope import changes, mcp_testplan

REPORT = """<report name="r"><package name="app">
  <sourcefile name="Store.groovy">
    <line nr="3" mi="0" ci="2" mb="1" cb="1"/>
    <line nr="4" mi="2" ci="0"/>
    <line nr="5" mi="1" ci="0"/>
    <line nr="7" mi="0" ci="3"/>
    <counter type="LINE" missed="2" covered="2"/>
  </sourcefile>
</package><counter type="LINE" missed="2" covered="2"/></report>"""
METHOD = {
    "kind": "method",
    "name": "save",
    "class": "app.Store",
    "path": "src/main/groovy/app/Store.groovy",
    "lines": [2, 8],
    "parameters": ["String name", "List<Item> items", "int count", "boolean force", "def extra"],
    "branches": 2,
}
SITE = {
    "caller": {
        "name": "saves",
        "container": "app.StoreSpec",
        "location": {"path": "src/test/groovy/app/StoreSpec.groovy"},
    }
}
IT_SITE = {
    "caller": {"name": "stores", "container": "app.StoreIT", "location": {"path": "src/it/groovy/app/StoreIT.groovy"}}
}


def test_pure_helpers() -> None:
    assert mcp_testplan.boundaries(METHOD["parameters"]) == [  # type: ignore[arg-type]
        "name: null and empty",
        "items: empty and null",
        "count: 0 and a negative value",
        "force: true and false",
    ]
    counters = {3: (0, 2, 1, 1), 4: (2, 0, 0, 0), 5: (1, 0, 0, 0), 7: (0, 3, 0, 0)}
    assert mcp_testplan.gaps(counters, 2, 8) == ["lines 4-5: no test runs them", "line 3: 1 of 2 branches never taken"]
    assert mcp_testplan.gaps(counters, 7, 7) == []
    tests = [
        {"className": "app.StoreSpec", "method": "saves", "sourceSet": "test"},
        {"className": "app.StoreIT", "method": "", "sourceSet": "it"},
        {"className": "app.StoreIT", "method": "x", "sourceSet": "test"},
    ]
    assert mcp_testplan.split_tests(tests) == {
        "unit": ["app.StoreSpec.saves"],
        "integration": ["app.StoreIT", "app.StoreIT.x"],
    }


def tools_for(tmp_path: Path, checked: list[dict[str, Any]], *, sites: list[dict[str, Any]]) -> Any:
    def explain_code(symbol: str | None = None, path: str | None = None) -> dict[str, Any]:
        if symbol:
            return {"items": [METHOD] if symbol == "Store.save" else []}
        return {"items": [{"name": "app.Store", "methods": [{**METHOD, "class": None, "path": None}]}]}

    others = {
        "explain_code": explain_code,
        "impact_of": lambda _symbol, _change: {"found": True, "reports": [{"tests": sites}]},
        "check": lambda **kwargs: checked.append(kwargs) or {"items": [
            {"path": "src/test/groovy/app/StoreSpec.groovy", "line": 9, "code": "GS047", "message": "same inputs"}]},
    }  # fmt: skip
    return mcp_testplan.test_plan_tools(tmp_path, 5, others, report_settings={}, verify_settings={})["test_plan"]


def test_a_fresh_report_lists_only_the_gaps(tmp_path: Path) -> None:
    source = tmp_path / "src/main/groovy/app/Store.groovy"
    source.parent.mkdir(parents=True)
    source.write_text("class Store {}\n", encoding="utf-8")
    report = tmp_path / "build/reports/jacoco/test/jacocoTestReport.xml"
    report.parent.mkdir(parents=True)
    report.write_text(REPORT, encoding="utf-8")
    os.utime(report, (source.stat().st_mtime + 5,) * 2)
    checked: list[dict[str, Any]] = []
    plan = tools_for(tmp_path, checked, sites=[SITE, IT_SITE])(symbol="Store.save")
    (method,) = plan["methods"]
    assert method["symbol"] == "app.Store.save" and method["lines"] == [2, 8]
    assert method["tests"] == {"unit": ["app.StoreSpec.saves"], "integration": ["app.StoreIT.stores"]}
    assert method["needed"] == ["lines 4-5: no test runs them", "line 3: 1 of 2 branches never taken"]
    assert method["coverage"].startswith("fresh report") and plan["needed"] == 2
    assert method["redundant"] == ["src/test/groovy/app/StoreSpec.groovy:9 [GS047] same inputs"]
    assert checked[0]["codes"] == ["GS044", "GS047"] and checked[0]["include"] == ["practices"]
    assert tools_for(tmp_path, [], sites=[])(symbol="Nope.x") == {"error": "no method Nope.x; give Class.method"}


def test_without_a_report_the_boundaries_are_needed(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    change = {"base": "main", "mode": "branch", "files": [
        {"path": "src/main/groovy/app/Store.groovy", "status": "modified", "ranges": [[4, 4]]},
        {"path": "src/test/groovy/app/StoreSpec.groovy", "status": "modified", "ranges": [[1, 2]]},
        {"path": "src/main/groovy/app/Gone.groovy", "status": "deleted", "ranges": []}]}  # fmt: skip
    monkeypatch.setattr(changes, "changed_files", lambda *_a, **_k: change)
    plan = tools_for(tmp_path, [], sites=[])()
    (method,) = plan["methods"]
    assert method["path"] == "src/main/groovy/app/Store.groovy" and method["symbol"] == "app.Store.save"
    assert method["needed"][0] == "a test that calls it: no test reaches it"
    assert "boundary name: null and empty" in method["needed"] and method["redundant"] == []
    assert method["coverage"].startswith("no fresh JaCoCo report")
    change["files"][0]["ranges"] = [[20, 30]]
    assert tools_for(tmp_path, [], sites=[])()["methods"] == []

    def broken(*_a: Any, **_k: Any) -> Any:
        raise ValueError("not a git repository")

    monkeypatch.setattr(changes, "changed_files", broken)
    assert tools_for(tmp_path, [], sites=[])() == {"error": "not a git repository"}
