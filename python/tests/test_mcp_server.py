"""The MCP tools against a fake engine session."""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any

import pytest

from groovyscope import mcp_server

ROOT = Path("/ws")


def loc(path: str, line: int = 4, col: int = 6) -> dict[str, Any]:
    return {"uri": f"file:///ws/{path}", "line": line, "character": col}


def symbol(name: str, kind: str = "method", path: str = "src/A.groovy") -> dict[str, Any]:
    return {"name": name, "kind": kind, "container": "app.A", "detail": f"void {name}()", "location": loc(path)}


@pytest.fixture
def engine() -> dict[str, Any]:
    state: dict[str, Any] = {"calls": [], "responses": {}}
    return state


@pytest.fixture
def tools(engine: dict[str, Any], tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    def call(method: str, params: dict[str, Any]) -> Any:
        engine["calls"].append((method, params))
        return engine["responses"].get(method, {})

    monkeypatch.setattr(
        mcp_server.daemon, "info", lambda _root, _environ: {"socket": "/s", "running": True, "status": {}, "log": "/l"}
    )
    return mcp_server.build_tools(ROOT, call, {"maxResults": 3, "maxBytes": 16000}, {})


def test_find_symbol_filters_kinds_and_pages(tools: dict[str, Any], engine: dict[str, Any]) -> None:
    engine["responses"]["groovyscope/symbols"] = {
        "symbols": [
            symbol("Greeter", "class"),
            symbol("greet"),
            symbol("greeting", "property"),
            symbol("GreeterSpec", "class"),
            symbol("x", "class"),
        ]
    }
    classes = tools["find_symbol"]("gree", kind="class")
    assert [s["name"] for s in classes["items"]] == ["Greeter", "GreeterSpec", "x"]
    assert classes["items"][0]["location"] == {"path": "src/A.groovy", "line": 5, "col": 7}
    everything = tools["find_symbol"]("gree")
    assert everything["truncated"] is True and everything["next_cursor"] == 3
    rest = tools["find_symbol"]("gree", cursor=3)
    assert [s["name"] for s in rest["items"]] == ["GreeterSpec", "x"]
    assert engine["calls"][0] == ("groovyscope/symbols", {"root": "/ws", "query": "gree"})


def test_goto_definition_and_references(tools: dict[str, Any], engine: dict[str, Any]) -> None:
    assert tools["goto_definition"]("src/A.groovy", 7, 3) == {"found": False}
    assert engine["calls"][-1][1] == {"root": "/ws", "uri": "file:///ws/src/A.groovy", "line": 6, "character": 2}
    engine["responses"]["groovyscope/definition"] = {
        "symbol": symbol("greet"),
        "confidence": "INFERRED",
        "inWorkspace": True,
    }
    found = tools["goto_definition"]("src/A.groovy", 7, 3)
    assert found["found"] and found["confidence"] == "INFERRED" and found["symbol"]["name"] == "greet"
    engine["responses"]["groovyscope/references"] = {
        "references": [
            {**loc("src/B.groovy", 1, 2), "confidence": "STATIC"},
            {**loc("src/C.groovy"), "confidence": "DYNAMIC_UNKNOWN"},
        ],
        "counts": {"STATIC": 1, "DYNAMIC_UNKNOWN": 1},
    }
    refs = tools["find_references"]("src/A.groovy", 7, 3, include_declaration=True)
    assert refs["items"][0] == {"path": "src/B.groovy", "line": 2, "col": 3, "confidence": "STATIC"}
    assert refs["counts"]["DYNAMIC_UNKNOWN"] == 1
    assert engine["calls"][-1][1]["includeDeclaration"] is True


def test_call_graph_and_impact(tools: dict[str, Any], engine: dict[str, Any]) -> None:
    site = {
        "caller": symbol("run"),
        "callee": symbol("greet"),
        "site": loc("src/Main.groovy", 6, 16),
        "confidence": "STATIC",
    }
    engine["responses"]["groovyscope/callGraph"] = {
        "direction": "in",
        "depth": 2,
        "nodes": [symbol("greet"), symbol("run")],
        "edges": [site],
    }
    graph = tools["call_graph"]("A#greet")
    assert graph["items"][0]["site"] == {"path": "src/Main.groovy", "line": 7, "col": 17}
    assert [n["name"] for n in graph["nodes"]] == ["greet", "run"]
    engine["responses"]["groovyscope/impact"] = [
        {
            "symbol": symbol("greet"),
            "change": "signature",
            "riskScore": 30,
            "riskLevel": "medium",
            "counts": {"direct": 1},
            "directCallers": [site],
            "transitiveCallers": [],
            "overrides": [symbol("greet", path="src/B.groovy")],
            "tests": [],
            "dynamicUnknowns": [],
            "suggestedChecks": ["update 1 direct call site(s)"],
        }
    ]
    impact = tools["impact_of"]("A#greet", change="signature")
    assert impact["found"] and impact["reports"][0]["riskLevel"] == "medium"
    assert impact["reports"][0]["overrides"][0]["location"]["path"] == "src/B.groovy"
    engine["responses"]["groovyscope/impact"] = []
    assert tools["impact_of"]("nothing")["found"] is False


def test_check_and_dynamic_code_and_status(tools: dict[str, Any], engine: dict[str, Any]) -> None:
    engine["responses"]["groovyscope/check"] = {
        "diagnostics": {
            "file:///ws/src/A.groovy": [
                {
                    "range": {"start": {"line": 3, "character": 0}},
                    "severity": 3,
                    "source": "codenarc",
                    "code": "X",
                    "message": "m",
                },
                {
                    "range": {"start": {"line": 1, "character": 0}},
                    "severity": 1,
                    "source": "groovyc",
                    "code": "syntax",
                    "message": "e",
                },
            ]
        },
        "summary": {"errors": 1},
    }
    checked = tools["check"](paths=["src/A.groovy"], profile="jenkins", include=["lint"])
    assert [f["severity"] for f in checked["items"]] == ["error", "info"]
    assert checked["summary"] == {"errors": 1}
    assert engine["calls"][-1][1]["profile"] == "jenkins"
    engine["responses"]["groovyscope/dynamicCode"] = {
        "findings": [{"kind": "grab", "symbol": "evil.UsesGrab", "detail": "@Grab", "location": loc("src/E.groovy")}],
        "counts": {"grab": 1},
    }
    dynamic = tools["find_dynamic_code"]()
    assert dynamic["items"][0]["kind"] == "grab" and dynamic["counts"] == {"grab": 1}
    engine["responses"]["groovyscope/status"] = {"engine": "0.5.0", "daemon": True}
    status = tools["workspace_status"]()
    assert status["engine"]["engine"] == "0.5.0" and status["daemon"]["running"] is True


def test_format_reports_and_applies(
    tools: dict[str, Any], engine: dict[str, Any], tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    target = tmp_path / "Messy.groovy"
    target.write_text("a  \n", encoding="utf-8")
    engine["responses"]["groovyscope/format"] = {
        "edits": {
            target.as_uri(): [
                {"range": {"start": {"line": 0, "character": 0}, "end": {"line": 1, "character": 0}}, "newText": "a\n"}
            ]
        },
        "checked": 1,
    }
    dry = tools["format"](paths=[str(target)])
    assert dry["changed"] == 1 and dry["files"][0]["linesChanged"] == 1 and dry["files"][0]["applied"] is False
    assert target.read_text(encoding="utf-8") == "a  \n"
    applied = tools["format"](paths=[str(target)], apply=True)
    assert applied["files"][0]["applied"] is True
    assert target.read_text(encoding="utf-8") == "a\n"


def test_server_lists_every_tool_and_answers_calls(engine: dict[str, Any]) -> None:
    engine["responses"]["groovyscope/symbols"] = {"symbols": [symbol("Greeter", "class")]}

    def call(method: str, params: dict[str, Any]) -> Any:
        return engine["responses"].get(method, {})

    server = mcp_server.build_server(ROOT, call, {"maxResults": 50, "maxBytes": 16000}, {})
    names = {tool.name for tool in asyncio.run(server.list_tools())}
    assert names == {
        "find_symbol",
        "goto_definition",
        "find_references",
        "call_graph",
        "impact_of",
        "check",
        "format",
        "find_dynamic_code",
        "workspace_status",
    }
    result = asyncio.run(server.call_tool("find_symbol", {"query": "gree"}))
    structured = result[1] if isinstance(result, tuple) else result
    assert "Greeter" in str(structured)


def test_compact_helpers() -> None:
    assert mcp_server.compact_location(None, ROOT) is None
    assert (
        mcp_server.compact_location(
            {"uri": "file:///elsewhere/X.groovy", "range": {"start": {"line": 0, "character": 0}}}, ROOT
        )["path"]
        == "/elsewhere/X.groovy"
    )
    assert mcp_server.compact_symbol({"name": "n", "kind": 5, "containerName": "c"}, ROOT)["kind"] == "class"
    assert mcp_server.to_uri(ROOT, "src/A.groovy") == "file:///ws/src/A.groovy"


def test_serve_opens_a_session_and_closes_it(monkeypatch: pytest.MonkeyPatch) -> None:
    events: list[tuple[str, Any]] = []
    session = {"sock": None}

    monkeypatch.setattr(mcp_server.rpc, "open_session", lambda path: events.append(("open", path)) or session)
    monkeypatch.setattr(
        mcp_server.rpc, "call", lambda _s, method, _params=None, timeout=None: events.append((method, timeout)) or {}
    )
    monkeypatch.setattr(
        mcp_server.rpc, "notify_session", lambda _s, method, _params=None: events.append((method, None))
    )
    monkeypatch.setattr(mcp_server.rpc, "close_session", lambda s: events.append(("close", s)))

    class _Server:
        def run(self, transport: str) -> None:
            events.append(("run", transport))
            # The tools call the engine through the session-bound function with a long timeout.
            self.call("groovyscope/status", {})

    def build(root: Path, call: Any, output: Any, environ: Any) -> _Server:
        server = _Server()
        server.call = call  # type: ignore[attr-defined]
        return server

    monkeypatch.setattr(mcp_server, "build_server", build)
    assert mcp_server.serve(ROOT, Path("/s.sock"), {}, {}) == 0
    assert events == [
        ("open", Path("/s.sock")),
        ("run", "stdio"),
        ("groovyscope/status", 600.0),
        ("close", session),
    ]
