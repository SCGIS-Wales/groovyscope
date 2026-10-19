"""explain_code, relationship_graph and check_naming over a fake engine, and the rename verification."""

from __future__ import annotations

import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

from groovyscope import mcp_server

CLASS_FACTS = {
    "kind": "class",
    "name": "app.OrderService",
    "simpleName": "OrderService",
    "package": "app",
    "uri": "file:///ws/src/app/OrderService.groovy",
    "startLine": 4,
    "endLine": 60,
    "modifiers": [],
    "annotations": ["CompileStatic"],
    "superClass": "app.Base",
    "interfaces": ["app.Priced"],
    "fields": [
        {
            "name": "sql",
            "type": "Sql",
            "visibility": "private",
            "final": True,
            "static": False,
            "property": False,
            "initialised": False,
            "line": 5,
        },
        {
            "name": "names",
            "type": "List<String>",
            "visibility": "property",
            "final": False,
            "static": False,
            "property": True,
            "initialised": True,
            "line": 6,
        },
    ],
    "methods": [
        {
            "name": "total",
            "signature": "BigDecimal total(List<BigDecimal> prices, boolean round)",
            "visibility": "public",
            "static": False,
            "abstract": False,
            "constructor": False,
            "returns": "BigDecimal",
            "dynamicReturn": False,
            "parameters": [{"name": "prices", "type": "List<BigDecimal>"}, {"name": "round", "type": "boolean"}],
            "startLine": 10,
            "endLine": 20,
            "branches": 4,
            "calls": [{"target": "app.OrderService.log", "confidence": "STATIC", "count": 2}]
            + [{"target": f"app.Other.m{i}", "confidence": "INFERRED", "count": 1} for i in range(9)],
            "callerCount": 3,
            "callers": ["app.Caller.run"],
            "reads": ["counter"],
            "writes": ["counter"],
            "throws": ["IllegalStateException"],
            "catches": [],
            "externals": ["sql", "file"],
        },
        {
            "name": "<init>",
            "signature": "OrderService(Sql)",
            "visibility": "public",
            "static": False,
            "abstract": False,
            "constructor": True,
            "returns": None,
            "dynamicReturn": False,
            "parameters": [{"name": "sql", "type": "Sql"}],
            "startLine": 8,
            "endLine": 9,
            "branches": 0,
            "calls": [],
            "callerCount": 0,
            "callers": [],
            "reads": [],
            "writes": ["sql"],
            "throws": [],
            "catches": [],
            "externals": [],
        },
    ],
    "responsibilities": [{"verb": "total", "methods": ["total"]}],
    "usedBy": ["app.Caller"],
    "uses": ["groovy.sql.Sql"],
    "callerCount": 3,
}

GRAPH = {
    "nodes": [
        {
            "id": "app.Base",
            "name": "Base",
            "kind": "class",
            "package": "app",
            "uri": "file:///ws/src/app/Base.groovy",
            "startLine": 0,
            "endLine": 3,
            "members": 0,
            "test": False,
        },
        {
            "id": "app.OrderService",
            "name": "OrderService",
            "kind": "class",
            "package": "app",
            "uri": "file:///ws/src/app/OrderService.groovy",
            "startLine": 4,
            "endLine": 60,
            "members": 3,
            "test": False,
        },
    ],
    "edges": [{"from": "app.OrderService", "to": "app.Base", "kind": "extends", "confidence": "STATIC", "count": 1}],
    "truncated": False,
    "totalClasses": 2,
}


def test_explain_code_compacts_class_and_method_facts(tools: dict[str, Any], engine: dict[str, Any]) -> None:
    engine["responses"]["groovyscope/explainCode"] = {"found": True, "items": [CLASS_FACTS]}
    found = tools["explain_code"](symbol="app.OrderService")
    assert found["found"] and engine["calls"][-1][1] == {"root": "/ws", "symbol": "app.OrderService"}
    item = found["items"][0]
    assert item["path"] == "src/app/OrderService.groovy" and item["lines"] == [5, 61]
    assert item["extends"] == "app.Base" and item["implements"] == ["app.Priced"]
    assert item["fields"][0] == {"name": "sql", "type": "Sql", "visibility": "private", "final": True}
    total = item["methods"][0]
    assert total["parameters"] == ["List<BigDecimal> prices", "boolean round"] and total["lines"] == [11, 21]
    assert len(total["calls"]) == 8 and total["callsMore"] == 2
    assert total["writes"] == ["counter"] and total["externals"] == ["sql", "file"]
    assert item["methods"][1]["constructor"] is True
    assert item["externals"] == ["file", "sql"] and item["usedBy"] == ["app.Caller"]
    method_item = {
        **CLASS_FACTS["methods"][0],
        "kind": "method",
        "class": "app.OrderService",
        "uri": CLASS_FACTS["uri"],
        "testing": {
            "collaborators": [{"target": "app.Repo.save", "visibility": "public", "stub": "spy"}],
            "tests": [
                {
                    "class": "app.OrderServiceTest",
                    "uri": "file:///ws/src/test/OrderServiceTest.groovy",
                    "helpers": ["build() line 3"],
                }
            ],
        },
    }
    engine["responses"]["groovyscope/explainCode"] = {"found": True, "items": [method_item]}
    by_path = tools["explain_code"](path="src/app/OrderService.groovy")
    assert engine["calls"][-1][1]["uri"] == "file:///ws/src/app/OrderService.groovy"
    assert by_path["items"][0]["kind"] == "method" and by_path["items"][0]["path"] == "src/app/OrderService.groovy"
    assert by_path["items"][0]["testing"]["tests"] == [
        {"class": "app.OrderServiceTest", "path": "src/test/OrderServiceTest.groovy", "helpers": ["build() line 3"]}
    ]
    assert by_path["items"][0]["testing"]["collaborators"][0]["target"] == "app.Repo.save"
    engine["responses"]["groovyscope/explainCode"] = {
        "found": True,
        "items": [
            {
                "kind": "field",
                "name": "sql",
                "type": "Sql",
                "class": "app.OrderService",
                "visibility": "private",
                "final": True,
                "static": False,
            }
        ],
    }
    assert tools["explain_code"](symbol="app.OrderService.sql")["items"][0]["type"] == "Sql"
    assert tools["explain_code"]()["error"].startswith("give a symbol")


def test_relationship_graph_returns_compact_graph_and_writes_drawio(
    local_tools: dict[str, Any], engine: dict[str, Any], tmp_path: Path
) -> None:
    engine["responses"]["groovyscope/graph"] = GRAPH
    result = local_tools["relationship_graph"](
        symbols=["app.OrderService"], kinds=["extends", "calls"], depth=2, mermaid=True
    )
    params = engine["calls"][-1][1]
    assert (
        params["symbols"] == ["app.OrderService"] and params["kinds"] == ["extends", "calls"] and params["depth"] == 2
    )
    assert params["level"] == "class" and params["maxNodes"] == 60
    assert [n["id"] for n in result["nodes"]] == ["app.Base", "app.OrderService"]
    assert result["edges"][0]["kind"] == "extends" and result["mermaid"].startswith("classDiagram")
    assert local_tools["relationship_graph"](kinds=["bogus"])["error"].startswith("unknown edge kind")
    written = local_tools["relationship_graph"](
        paths=["src/app/OrderService.groovy"], drawio_path="docs/classes.drawio"
    )
    assert written["written"] == "docs/classes.drawio" and written["page"]["orientation"] in {"portrait", "landscape"}
    assert "layoutProblems" not in written
    assert written["lines"]["layout"] == "basic"  # the fake engine has no layout: the basic layout stands in
    engine["responses"]["groovyscope/layout"] = {
        "boxes": {"app.Base": {"x": 0.0, "y": 0.0}, "app.OrderService": {"x": 0.0, "y": 110.0}},
        "routes": {"0": [[60.0, 110.0], [60.0, 46.0]]},
        "width": 160.0,
        "height": 156.0,
    }
    layered = local_tools["relationship_graph"](
        paths=["src/app/OrderService.groovy"], drawio_path="docs/classes.drawio"
    )
    assert layered["lines"] == {"layout": "layered", "crossings": 0, "overlaps": 0}
    engine["responses"]["groovyscope/graph"] = {
        **GRAPH,
        "edges": [*GRAPH["edges"], {"from": "app.OrderService", "to": "app.Base", "kind": "calls", "effect": "write"}],
    }
    with_effects = local_tools["relationship_graph"](paths=["src/app"], drawio_path="docs/classes.drawio")
    assert with_effects["lines"]["calls"] == {"write": 1}
    engine["responses"]["groovyscope/graph"] = GRAPH
    assert engine["calls"][-1][0] == "groovyscope/layout" and engine["calls"][-1][1]["links"][0]["hierarchy"] is True
    root = ET.parse(tmp_path / "docs" / "classes.drawio").getroot()  # noqa: S314 - our own output
    assert root.find("diagram").get("name") == "classes"  # type: ignore[union-attr]
    assert local_tools["relationship_graph"](drawio_path="../outside.drawio")["error"].endswith("outside the workspace")
    assert local_tools["relationship_graph"](drawio_path="notes.txt")["error"].endswith("must end in .drawio")
    engine["responses"]["groovyscope/graph"] = {
        "nodes": [],
        "edges": [],
        "truncated": False,
        "totalClasses": 0,
        "note": "no workspace class matches",
    }
    assert local_tools["relationship_graph"](symbols=["Nope"])["note"] == "no workspace class matches"


def test_check_naming_combines_findings_and_declaration_facts(tools: dict[str, Any], engine: dict[str, Any]) -> None:
    uri = "file:///ws/src/app/OrderService.groovy"
    engine["responses"]["groovyscope/check"] = {
        "diagnostics": {
            uri: [
                {
                    "range": {"start": {"line": 5, "character": 4}},
                    "severity": 3,
                    "source": "groovyscope",
                    "code": "GS024",
                    "message": "abbreviation",
                },
                {
                    "range": {"start": {"line": 9, "character": 4}},
                    "severity": 2,
                    "source": "codenarc",
                    "code": "NoDef",
                    "message": "def",
                },
            ]
        },
        "summary": {},
    }
    engine["responses"]["groovyscope/explainCode"] = {"found": True, "items": [CLASS_FACTS]}
    result = tools["check_naming"](paths=["src/app/OrderService.groovy"])
    check_params = next(p for m, p in engine["calls"] if m == "groovyscope/check")
    assert check_params["include"] == ["practices"] and check_params["uris"] == [uri]
    assert [f["code"] for f in result["items"]] == ["GS024"]
    names = [(d["name"], d["kind"]) for d in result["declarations"]]
    assert names == [("OrderService", "class"), ("sql", "field"), ("names", "property"), ("total", "method")]
    total = result["declarations"][-1]
    assert total["writes"] == ["counter"] and total["returns"] == "BigDecimal" and total["callerCount"] == 3
    assert result["declarations"][0]["responsibilities"] == ["total"]
    assert "note" not in result
    whole = tools["check_naming"]()
    assert whole["declarations"] == [] and whole["note"].startswith("pass paths")


def test_rename_verifies_by_recompiling_and_rolls_back_new_errors(
    local_tools: dict[str, Any], engine: dict[str, Any], tmp_path: Path
) -> None:
    source = tmp_path / "src" / "A.groovy"
    source.parent.mkdir()
    source.write_text("class A {\n  int count = 1\n}\n", encoding="utf-8")
    uri = source.as_uri()
    engine["responses"]["groovyscope/rename"] = {
        "edits": {
            uri: [
                {
                    "range": {"start": {"line": 1, "character": 6}, "end": {"line": 1, "character": 11}},
                    "newText": "total",
                }
            ]
        }
    }
    checks: list[dict[str, Any]] = [{"diagnostics": {}}, {"diagnostics": {}}]
    engine["responses"]["groovyscope/check"] = checks[0]
    dry = local_tools["rename"]("src/A.groovy", 2, 7, "total")
    assert dry["renamed"] is False and dry["files"][0]["linesChanged"] == 1
    assert source.read_text(encoding="utf-8").count("count") == 1
    applied = local_tools["rename"]("src/A.groovy", 2, 7, "total", apply=True)
    assert applied["renamed"] is True and applied["verified"].startswith("the changed files compile")
    assert "total" in source.read_text(encoding="utf-8")
    source.write_text("class A {\n  int count = 1\n}\n", encoding="utf-8")
    answers = iter(
        [
            {"diagnostics": {}},
            {
                "diagnostics": {
                    uri: [
                        {
                            "range": {"start": {"line": 1, "character": 0}},
                            "severity": 1,
                            "source": "groovyc",
                            "code": None,
                            "message": "unable to resolve",
                        }
                    ]
                }
            },
        ]
    )
    original_call = engine["responses"]

    def call(method: str, params: dict[str, Any]) -> Any:
        engine["calls"].append((method, params))
        if method == "groovyscope/check":
            return next(answers)
        return original_call.get(method, {})

    tools = mcp_server.build_tools(tmp_path, call, {"maxResults": 3, "maxBytes": 16000}, {})
    rolled = tools["rename"]("src/A.groovy", 2, 7, "total", apply=True)
    assert rolled["renamed"] is False and rolled["rolledBack"] is True
    assert "1 new compile error" in rolled["reason"]
    assert source.read_text(encoding="utf-8") == "class A {\n  int count = 1\n}\n"
