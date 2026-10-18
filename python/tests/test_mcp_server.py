"""The MCP tools against a fake engine session."""

from __future__ import annotations

import asyncio
import json
import threading
from pathlib import Path
from typing import Any

import pytest

from groovyscope import mcp_server

ROOT = Path("/ws")


def loc(path: str, line: int = 4, col: int = 6) -> dict[str, Any]:
    return {"uri": f"file:///ws/{path}", "line": line, "character": col}


def symbol(name: str, kind: str = "method", path: str = "src/A.groovy") -> dict[str, Any]:
    return {"name": name, "kind": kind, "container": "app.A", "detail": f"void {name}()", "location": loc(path)}


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
    local_tools: dict[str, Any], engine: dict[str, Any], tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    tools = local_tools
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
    dry = tools["format"](paths=["Messy.groovy"])
    assert dry["changed"] == 1 and dry["files"][0]["linesChanged"] == 1 and dry["files"][0]["applied"] is False
    assert target.read_text(encoding="utf-8") == "a  \n"
    applied = tools["format"](paths=["Messy.groovy"], apply=True)
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
        "refresh_classpath",
        "explain",
        "review",
        "review_changes",
        "coverage_gaps",
        "explain_code",
        "relationship_graph",
        "check_naming",
        "fix",
        "profile_status",
        "hover",
        "type_definition",
        "document_symbols",
        "rename",
        "read_symbol",
        "find_code",
        "find_duplicates",
        "workspace_overview",
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

    monkeypatch.setattr(mcp_server.rpc, "open_channel", lambda path: events.append(("open", path)) or session)
    monkeypatch.setattr(
        mcp_server.rpc,
        "channel_call",
        lambda _s, method, _params=None, timeout=None: events.append((method, timeout)) or {},
    )
    monkeypatch.setattr(mcp_server.rpc, "close_channel", lambda s: events.append(("close", s)))

    class _Server:
        def run(self, transport: str) -> None:
            events.append(("run", transport))
            # The tools call the engine through the channel-bound function within the request budget.
            self.call("groovyscope/status", {})

    def build(root: Path, call: Any, output: Any, environ: Any) -> _Server:
        server = _Server()
        server.call = call  # type: ignore[attr-defined]
        return server

    monkeypatch.setattr(mcp_server, "build_server", build)
    assert mcp_server.serve(ROOT, Path("/s.sock"), {"mcp": {"requestTimeoutSeconds": 90}}, {}) == 0
    assert events == [
        ("open", Path("/s.sock")),
        ("run", "stdio"),
        ("groovyscope/status", 90.0),
        ("close", session),
    ]


def test_explain_review_and_fix(
    tools: dict[str, Any], local_tools: dict[str, Any], engine: dict[str, Any], tmp_path: Path
) -> None:
    engine["responses"]["groovyscope/explain"] = {"code": "GS001", "title": "Integer division with /", "why": "w"}
    assert tools["explain"]("GS001") == {"found": True, "code": "GS001", "title": "Integer division with /", "why": "w"}
    engine["responses"]["groovyscope/explain"] = None
    assert tools["explain"]("Nope") == {"found": False, "code": "Nope"}

    engine["responses"]["groovyscope/check"] = {
        "diagnostics": {
            "file:///ws/src/A.groovy": [
                {
                    "range": {"start": {"line": 4, "character": 2}},
                    "severity": 2,
                    "source": "groovyscope",
                    "code": "GS001",
                    "message": "m1",
                },
                {
                    "range": {"start": {"line": 1, "character": 0}},
                    "severity": 1,
                    "source": "groovyscope",
                    "code": "GS006",
                    "message": "m2",
                },
                {
                    "range": {"start": {"line": 2, "character": 0}},
                    "severity": 3,
                    "source": "codenarc",
                    "code": "SomeRule",
                    "message": "m3",
                },
            ]
        },
        "summary": {"errors": 1, "warnings": 1},
    }
    reviewed = tools["review"](paths=["src/A.groovy"], top=1)
    assert [g["group"] for g in reviewed["groups"]] == ["security", "numbers", "style"]
    assert reviewed["groups"][0]["findings"][0]["code"] == "GS006"
    assert reviewed["groups"][1]["title"] == "Numbers and money"
    assert [c for c in engine["calls"] if c[0] == "groovyscope/check"][-1][1]["include"] == []
    assert "profiles" not in reviewed

    target = tmp_path / "Fix.groovy"
    target.write_text("int x = a / b\n", encoding="utf-8")
    engine["responses"]["groovyscope/fix"] = {
        "edits": {
            target.as_uri(): [
                {
                    "range": {"start": {"line": 0, "character": 8}, "end": {"line": 0, "character": 13}},
                    "newText": "a.intdiv(b)",
                }
            ]
        },
        "applied": {target.as_uri(): ["GS001"]},
        "rejected": {(tmp_path / "src" / "B.groovy").as_uri(): "does not parse"},
    }
    dry = local_tools["fix"](paths=["Fix.groovy"])
    assert dry["files"][0]["codes"] == ["GS001"] and dry["files"][0]["applied"] is False
    assert dry["rejected"] == {"src/B.groovy": "does not parse"}
    assert target.read_text(encoding="utf-8") == "int x = a / b\n"
    local_tools["fix"](paths=["Fix.groovy"], codes=["GS001"], apply=True)
    assert target.read_text(encoding="utf-8") == "int x = a.intdiv(b)\n"
    assert engine["calls"][-1][1]["codes"] == ["GS001"]


def test_profile_status_and_review_notes(tools: dict[str, Any], engine: dict[str, Any]) -> None:
    engine["responses"]["groovyscope/profile"] = {"governed": True, "profile": "demo-runtime", "targetGroovy": "5.1"}
    engine["responses"]["groovyscope/check"] = {"diagnostics": {}, "summary": {}}
    status = tools["profile_status"]("plugins/demo/DemoPlugin.groovy")
    assert status == {
        "path": "plugins/demo/DemoPlugin.groovy",
        "governed": True,
        "profile": "demo-runtime",
        "targetGroovy": "5.1",
    }
    assert engine["calls"][-1] == (
        "groovyscope/profile",
        {"root": "/ws", "uri": "file:///ws/plugins/demo/DemoPlugin.groovy"},
    )
    reviewed = tools["review"](paths=["plugins/demo/DemoPlugin.groovy"])
    assert reviewed["profiles"] == {"plugins/demo/DemoPlugin.groovy": "demo-runtime"}
    engine["responses"]["groovyscope/profile"] = None
    assert tools["profile_status"]("src/A.groovy") == {"path": "src/A.groovy", "governed": False}


def test_writes_stay_inside_the_workspace_and_keep_line_endings(
    local_tools: dict[str, Any], engine: dict[str, Any], tmp_path: Path
) -> None:
    outside = tmp_path.parent / f"{tmp_path.name}-outside.groovy"
    outside.write_text("x\n", encoding="utf-8")
    inside = tmp_path / "Crlf.groovy"
    inside.write_bytes("def s = '\U0001f600'; int a = 1\r\nint b = 2\r\n".encode())
    edit = {"range": {"start": {"line": 0, "character": 18}, "end": {"line": 0, "character": 19}}, "newText": "z"}
    engine["responses"]["groovyscope/fix"] = {
        "edits": {inside.as_uri(): [edit], outside.as_uri(): [edit], (tmp_path / "notes.txt").as_uri(): [edit]},
        "applied": {inside.as_uri(): ["X"]},
    }
    (tmp_path / "notes.txt").write_text("text\n", encoding="utf-8")
    result = local_tools["fix"](apply=True)
    assert inside.read_bytes() == "def s = '\U0001f600'; int z = 1\r\nint b = 2\r\n".encode()
    assert outside.read_text(encoding="utf-8") == "x\n"
    assert set(result["rejected"].values()) == {"not a Groovy source inside the workspace"}
    with pytest.raises(ValueError, match="outside the workspace"):
        local_tools["fix"](paths=["../elsewhere.groovy"])


def test_session_caller_reconnects_once_after_the_engine_went_away() -> None:
    opened: list[Path] = []
    attempts: list[str] = []

    def call(session: dict[str, Any], method: str, _params: Any = None, timeout: float | None = None) -> Any:
        attempts.append(session["name"])
        if session["name"] == "first":
            msg = "engine closed the session"
            raise ConnectionError(msg)
        return {"ok": method}

    names = iter(["first", "second"])

    def opener(path: Path) -> dict[str, Any]:
        opened.append(path)
        return {"name": next(names)}

    caller, close = mcp_server.session_caller(
        Path("/a.sock"), lambda: Path("/b.sock"), open_session=opener, call_fn=call, close_fn=lambda _s: None
    )
    assert caller("groovyscope/status", {}) == {"ok": "groovyscope/status"}
    assert opened == [Path("/a.sock"), Path("/b.sock")] and attempts == ["first", "second"]
    close()
    no_retry, _ = mcp_server.session_caller(
        Path("/a.sock"), None, open_session=lambda _p: {"name": "first"}, call_fn=call, close_fn=lambda _s: None
    )
    with pytest.raises(ConnectionError):
        no_retry("groovyscope/status", {})


def test_session_caller_runs_calls_concurrently_within_the_in_flight_limit() -> None:
    running = 0
    peak = 0
    gate = threading.Lock()
    release = threading.Event()

    def call(_session: dict[str, Any], method: str, params: Any = None, timeout: float | None = None) -> Any:
        nonlocal running, peak
        with gate:
            running += 1
            peak = max(peak, running)
        release.wait(5)
        with gate:
            running -= 1
        return {"method": method, "params": params, "timeout": timeout}

    caller, close = mcp_server.session_caller(
        Path("/a.sock"),
        None,
        open_session=lambda _p: {"name": "one"},
        call_fn=call,
        close_fn=lambda _s: None,
        limits={"maxInFlight": 2, "requestTimeoutSeconds": 30},
    )
    results: list[Any] = []
    threads = [
        threading.Thread(target=lambda i=i: results.append(caller("groovyscope/check", {"n": i}))) for i in range(3)
    ]
    for thread in threads:
        thread.start()
    threading.Event().wait(0.3)
    assert peak == 2  # the third waits for a slot instead of hitting the engine
    release.set()
    for thread in threads:
        thread.join(10)
    assert sorted(r["params"]["n"] for r in results) == [0, 1, 2]
    assert all(r["timeout"] == 30.0 for r in results)
    close()


def test_session_caller_joins_identical_calls_and_reports_a_timed_out_one() -> None:
    calls: list[str] = []
    release = threading.Event()

    def call(_session: dict[str, Any], method: str, params: Any = None, timeout: float | None = None) -> Any:
        calls.append(method)
        if method == "hang":
            msg = "hang did not answer"
            raise TimeoutError(msg)
        release.wait(5)
        return {"echo": params}

    caller, close = mcp_server.session_caller(
        Path("/a.sock"),
        None,
        open_session=lambda _p: {},
        call_fn=call,
        close_fn=lambda _s: None,
        limits={"requestTimeoutSeconds": 20, "maxInFlight": 4},
    )
    results: list[Any] = []
    same = [
        threading.Thread(target=lambda: results.append(caller("groovyscope/check", {"uris": ["a"]}))) for _ in range(3)
    ]
    for thread in same:
        thread.start()
    threading.Event().wait(0.3)
    release.set()
    for thread in same:
        thread.join(10)
    assert calls == ["groovyscope/check"]  # one engine request served three identical calls
    assert results == [{"echo": {"uris": ["a"]}}] * 3
    with pytest.raises(RuntimeError, match="did not answer within 20s; the engine was told to stop it"):
        caller("hang", {})
    assert mcp_server.request_limits(None) == (100.0, 4)
    assert mcp_server.request_limits({"requestTimeoutSeconds": 0, "maxInFlight": 0}) == (1.0, 1)
    close()


def test_insight_tools_and_rename(
    tools: dict[str, Any], local_tools: dict[str, Any], engine: dict[str, Any], tmp_path: Path
) -> None:
    engine["responses"]["groovyscope/hover"] = {"markdown": "`String greet(String name)`"}
    assert tools["hover"]("src/A.groovy", 4, 6) == {"found": True, "markdown": "`String greet(String name)`"}
    assert engine["calls"][-1] == (
        "groovyscope/hover",
        {"root": "/ws", "uri": "file:///ws/src/A.groovy", "line": 3, "character": 5},
    )
    engine["responses"]["groovyscope/hover"] = None
    assert tools["hover"]("src/A.groovy", 1, 1) == {"found": False}
    engine["responses"]["groovyscope/typeDefinition"] = {"symbol": symbol("String", "class"), "confidence": "STATIC"}
    assert tools["type_definition"]("src/A.groovy", 1, 1)["symbol"]["name"] == "String"
    engine["responses"]["groovyscope/typeDefinition"] = None
    assert tools["type_definition"]("src/A.groovy", 1, 1) == {"found": False}
    member = symbol("greet")
    klass = {**symbol("A", "class"), "children": [{**member, "children": [symbol("local", "variable")]}]}
    engine["responses"]["groovyscope/documentSymbols"] = {"symbols": [klass]}
    engine["responses"]["groovyscope/documentSymbols"] = {"symbols": [{**klass, "endLine": 40}], "lines": 41}
    outline = tools["document_symbols"]("src/A.groovy", depth=1)
    assert outline["items"][0]["name"] == "A" and outline["items"][0]["members"] == 1
    assert outline["items"][0]["lines"] == [5, 41] and outline["fileLines"] == 41
    deep = tools["document_symbols"]("src/A.groovy", depth=3)
    assert deep["items"][1]["name"] == "greet" and deep["items"][1]["in"] == "A"
    assert deep["items"][1]["children"][0]["name"] == "local"
    methods = tools["document_symbols"]("src/A.groovy", depth=2, kinds=["method"], name="gre")
    assert [i["name"] for i in methods["items"]] == ["A", "greet"]

    engine["responses"]["groovyscope/rename"] = {"refused": "matched by name only"}
    assert local_tools["rename"]("A.groovy", 1, 1, "b") == {"renamed": False, "refused": "matched by name only"}
    target = tmp_path / "A.groovy"
    target.write_text("int a = 1\nprintln a\n", encoding="utf-8")
    first = {"range": {"start": {"line": 0, "character": 4}, "end": {"line": 0, "character": 5}}, "newText": "b"}
    second = {"range": {"start": {"line": 1, "character": 8}, "end": {"line": 1, "character": 9}}, "newText": "b"}
    engine["responses"]["groovyscope/rename"] = {
        "edits": {target.as_uri(): [first, second], "file:///etc/hosts.groovy": [first]}
    }
    dry = local_tools["rename"]("A.groovy", 1, 5, "b")
    assert dry["files"] == [{"path": "A.groovy", "edits": 2, "linesChanged": 2}] and "skipped" in dry
    assert target.read_text(encoding="utf-8") == "int a = 1\nprintln a\n"
    applied = local_tools["rename"]("A.groovy", 1, 5, "b", apply=True)
    assert applied["renamed"] is True and target.read_text(encoding="utf-8") == "int b = 1\nprintln b\n"


def test_results_are_compact_and_annotated(engine: dict[str, Any]) -> None:
    engine["responses"]["groovyscope/symbols"] = {"symbols": [symbol("Greeter", "class")]}

    def call(method: str, params: dict[str, Any]) -> Any:
        return engine["responses"].get(method, {})

    server = mcp_server.build_server(ROOT, call, {"maxResults": 50, "maxBytes": 16000}, {})
    tools = {tool.name: tool for tool in asyncio.run(server.list_tools())}
    assert tools["check"].annotations.read_only_hint is True and tools["check"].annotations.open_world_hint is False
    assert tools["rename"].annotations.read_only_hint is False and tools["rename"].title == "Rename a symbol"
    result = asyncio.run(server.call_tool("find_symbol", {"query": "gree"}))
    text = result.content[0].text
    assert "\n" not in text and json.loads(text)["items"][0]["name"] == "Greeter"
    assert result.structured_content is None
    structured = mcp_server.build_server(ROOT, call, {"maxResults": 50, "structured": True}, {})
    again = asyncio.run(structured.call_tool("find_symbol", {"query": "gree"}))
    assert again.structured_content["items"][0]["name"] == "Greeter"
    assert mcp_server.clip("x" * 10, 4) == "xxx…" and mcp_server.clip(5, 1) == 5


def test_search_tools(tools: dict[str, Any], engine: dict[str, Any]) -> None:
    engine["responses"]["groovyscope/readSymbol"] = {
        "found": True,
        "name": "greet",
        "kind": "method",
        "container": "app.A",
        "startLine": 9,
        "endLine": 11,
        "text": "void greet() {\n    println 'hi'\n}",
        "truncated": False,
    }
    read = tools["read_symbol"]("src/A.groovy", "A.greet", line=10)
    assert read["source"] == "10: void greet() {\n11:     println 'hi'\n12: }" and read["lines"] == [10, 12]
    assert engine["calls"][-1][1]["line"] == 9 and engine["calls"][-1][1]["maxChars"] == 16000
    engine["responses"]["groovyscope/readSymbol"] = {
        "found": False,
        "candidates": [{"name": "greet", "kind": "method", "container": "A", "line": 3}],
    }
    assert tools["read_symbol"]("src/A.groovy", "gree") == {
        "found": False,
        "candidates": [{"name": "greet", "kind": "method", "in": "A", "line": 4}],
    }

    engine["responses"]["groovyscope/findCode"] = {
        "matches": [
            {
                "uri": "file:///ws/src/A.groovy",
                "line": 4,
                "endLine": 6,
                "detail": "catch (Exception e)",
                "snippet": "} catch (Exception e) {",
                "enclosing": "A.run",
                "handling": "logged",
                "catches": "Exception",
                "variable": "e",
                "code": "CatchException",
                "implicitType": None,
            }
        ],
        "count": 1,
    }
    found = tools["find_code"]("catch", type="Exception", rethrows=False)
    assert found["items"][0] == {
        "path": "src/A.groovy",
        "line": 5,
        "endLine": 7,
        "detail": "catch (Exception e)",
        "snippet": "} catch (Exception e) {",
        "in": "A.run",
        "handling": "logged",
        "catches": "Exception",
        "variable": "e",
        "code": "CatchException",
    }
    assert found["byHandling"] == {"logged": 1}
    assert list(found["meaning"]) == ["logged"]
    assert engine["calls"][-1][1] == {
        "root": "/ws",
        "uris": [],
        "kind": "catch",
        "type": "Exception",
        "rethrows": False,
    }
    assert tools["find_code"]("nonsense")["kinds"][0] == "catch"

    engine["responses"]["groovyscope/duplicates"] = {
        "groups": [
            {
                "lines": 8,
                "similarity": "exact",
                "occurrences": [
                    {"uri": "file:///ws/src/A.groovy", "line": 1, "endLine": 8, "enclosing": "A.a"},
                    {"uri": "file:///ws/src/B.groovy", "line": 10, "endLine": 17},
                ],
            }
        ],
        "count": 1,
    }
    duplicates = tools["find_duplicates"](min_lines=8)
    assert duplicates["items"][0]["at"] == [
        {"path": "src/A.groovy", "lines": [2, 9], "in": "A.a"},
        {"path": "src/B.groovy", "lines": [11, 18]},
    ]
    assert engine["calls"][-1][1]["minLines"] == 8 and engine["calls"][-1][1]["minStatements"] == 3

    engine["responses"]["groovyscope/overview"] = {
        "files": 2,
        "lines": 50,
        "packages": [{"name": "app", "files": 2, "classes": 2, "lines": 50}],
        "largestFiles": [{"uri": "file:///ws/src/A.groovy", "lines": 40, "classes": 1, "members": 3}],
        "largestClasses": [],
        "testFiles": 0,
        "untested": ["app.A"],
    }
    overview = tools["workspace_overview"]()
    assert overview["largestFiles"] == [{"lines": 40, "classes": 1, "members": 3, "path": "src/A.groovy"}]
    assert overview["untested"] == ["app.A"]


def test_check_filters_counts_and_missing_dependencies(tools: dict[str, Any], engine: dict[str, Any]) -> None:
    def item(line: int, severity: int, code: str, *, fixable: bool = False) -> dict[str, Any]:
        found = {
            "range": {"start": {"line": line, "character": 0}},
            "severity": severity,
            "source": "x",
            "code": code,
            "message": "m",
        }
        return {**found, "data": {"fixable": True}} if fixable else found

    engine["responses"]["groovyscope/check"] = {
        "diagnostics": {
            "file:///ws/src/A.groovy": [
                item(0, 2, "GS100"),
                item(1, 1, "GS006"),
                item(2, 2, "UnnecessaryGString", fixable=True),
                item(3, 3, "LineLength"),
                item(4, 3, "LineLength"),
            ]
        },
        "summary": {},
    }
    everything = tools["check"]()
    assert [f["code"] for f in everything["items"]] == ["GS006", "UnnecessaryGString", "LineLength"]  # page of 3
    assert everything["byCode"] == {"LineLength": 2, "GS006": 1, "UnnecessaryGString": 1}
    assert everything["missingDependencies"]["files"] == 1
    assert everything["items"][1]["fixable"] is True and "fixable" not in everything["items"][0]
    assert [f["code"] for f in tools["check"](max_priority=2)["items"]] == ["GS006", "UnnecessaryGString"]
    assert [f["code"] for f in tools["check"](codes=["LineLength"])["items"]] == ["LineLength", "LineLength"]
    reviewed = tools["review"](paths=["src/A.groovy"], max_priority=1)
    assert reviewed["byCode"] == {"GS006": 1} and reviewed["missingDependencies"]["files"] == 1


def test_review_changes_composes_findings_impact_and_risky_sites(
    tools: dict[str, Any], engine: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    uri = "file:///ws/src/A.groovy"
    engine["responses"]["groovyscope/check"] = {
        "diagnostics": {
            uri: [
                {
                    "range": {"start": {"line": 3, "character": 0}},
                    "severity": 2,
                    "source": "codenarc",
                    "code": "NoDef",
                    "message": "m",
                },
                {
                    "range": {"start": {"line": 20, "character": 0}},
                    "severity": 2,
                    "source": "codenarc",
                    "code": "NoDef",
                    "message": "m",
                },
                {
                    "range": {"start": {"line": 0, "character": 0}},
                    "severity": 2,
                    "source": "groovyscope",
                    "code": "GS100",
                    "message": "deps",
                },
            ]
        },
        "summary": {"errors": 0, "warnings": 2, "information": 0, "files": 1},
    }
    engine["responses"]["groovyscope/documentSymbols"] = {
        "symbols": [
            {
                "name": "A",
                "kind": "class",
                "location": loc("src/A.groovy", 0),
                "endLine": 30,
                "children": [{"name": "run", "kind": "method", "location": loc("src/A.groovy", 2), "endLine": 6}],
            }
        ],
        "lines": 31,
    }
    engine["responses"]["groovyscope/impact"] = [
        {
            "symbol": symbol("run"),
            "riskLevel": "medium",
            "riskScore": 30,
            "counts": {"direct": 2},
            "tests": [
                {"caller": symbol("t"), "callee": symbol("run"), "site": loc("src/T.groovy"), "confidence": "STATIC"}
            ],
            "dynamicUnknowns": [],
            "directCallers": [],
            "transitiveCallers": [],
            "overrides": [],
            "suggestedChecks": [],
        }
    ]
    engine["responses"]["groovyscope/findCode"] = {
        "matches": [
            {
                "uri": uri,
                "line": 3,
                "endLine": 4,
                "detail": "return null",
                "snippet": "return null",
                "enclosing": "A.run",
            }
        ],
        "count": 1,
    }
    change = {
        "base": "main",
        "mode": "branch",
        "files": [{"path": "src/A.groovy", "status": "modified", "ranges": [[3, 5]]}],
    }
    monkeypatch.setattr(mcp_server.changes, "changed_files", lambda *_a, **_k: change)
    result = tools["review_changes"]()
    assert result["verdict"] == "approve with comments: 1 warning(s) to fix, 3 risky construct(s) added"
    assert result["counts"] == {"blocking": 0, "shouldFix": 1, "nits": 0}
    assert result["shouldFix"][0]["line"] == 4
    assert result["elsewhere"] == {"count": 1, "byCode": {"NoDef": 1}}
    assert result["missingDependencies"]["files"] == 1
    assert [i["symbol"] for i in result["impact"]] == ["A", "A.run"]
    assert result["impact"][1]["tests"] == 1 and result["impact"][1]["callers"] == {"direct": 2}
    assert result["riskyCount"] == 3 and {r["kind"] for r in result["risky"]} == {"catch", "def", "return-null"}
    impact_calls = [p for m, p in engine["calls"] if m == "groovyscope/impact"]
    assert [c["symbol"] for c in impact_calls] == ["A", "A#run"]
    monkeypatch.setattr(
        mcp_server.changes, "changed_files", lambda *_a, **_k: {"base": "main", "mode": "branch", "files": []}
    )
    assert tools["review_changes"]()["verdict"].startswith("nothing to review")

    def refuse(*_a: object, **_k: object) -> object:
        msg = "git is not installed"
        raise ValueError(msg)

    monkeypatch.setattr(mcp_server.changes, "changed_files", refuse)
    assert tools["review_changes"]() == {"error": "git is not installed"}
    assert mcp_server.TOOL_INFO["review_changes"][1] is True
    assert len(mcp_server.TOOL_INFO) == 27


SARIF_LOG = {
    "version": "2.1.0",
    "runs": [
        {
            "tool": {"driver": {"name": "SpotBugs"}},
            "results": [
                {
                    "ruleId": "NP_NULL",
                    "level": "error",
                    "message": {"text": "null"},
                    "locations": [
                        {
                            "physicalLocation": {
                                "artifactLocation": {"uri": "src/A.groovy"},
                                "region": {"startLine": 4},
                            }
                        }
                    ],
                },
                {
                    "ruleId": "DM_X",
                    "level": "warning",
                    "message": {"text": "x"},
                    "locations": [
                        {
                            "physicalLocation": {
                                "artifactLocation": {"uri": "src/B.groovy"},
                                "region": {"startLine": 9},
                            }
                        }
                    ],
                },
            ],
        }
    ],
}

JACOCO_XML = """<?xml version="1.0" encoding="UTF-8"?>
<report name="demo">
  <package name="">
    <class name="A" sourcefilename="A.groovy">
      <method name="run" desc="()V" line="4"><counter type="LINE" missed="3" covered="0"/></method>
      <counter type="LINE" missed="3" covered="2"/>
    </class>
  </package>
  <counter type="LINE" missed="3" covered="2"/>
</report>
"""


def write_reports(tmp_path: Path) -> None:
    (tmp_path / "src").mkdir(exist_ok=True)
    (tmp_path / "src" / "A.groovy").write_text("class A {}\n", encoding="utf-8")
    sarif = tmp_path / "build" / "reports" / "spotbugs" / "spotbugs.sarif.json"
    sarif.parent.mkdir(parents=True)
    sarif.write_text(json.dumps(SARIF_LOG), encoding="utf-8")
    jacoco = tmp_path / "build" / "reports" / "jacoco" / "test" / "jacocoTestReport.xml"
    jacoco.parent.mkdir(parents=True)
    jacoco.write_text(JACOCO_XML, encoding="utf-8")


def test_check_and_review_merge_the_builds_sarif(
    local_tools: dict[str, Any], engine: dict[str, Any], tmp_path: Path
) -> None:
    write_reports(tmp_path)
    engine["responses"]["groovyscope/check"] = {"diagnostics": {}, "summary": {"files": 1}}
    found = local_tools["check"]()
    assert found["imported"] == {"spotbugs": 2}
    assert [f["code"] for f in found["items"]] == ["NP_NULL", "DM_X"]
    assert found["items"][0]["source"] == "spotbugs" and found["items"][0]["severity"] == "error"
    only_a = local_tools["check"](paths=["src/A.groovy"])
    assert [f["code"] for f in only_a["items"]] == ["NP_NULL"]
    assert local_tools["check"](codes=["DM_X"])["byCode"] == {"DM_X": 1}
    review = local_tools["review"]()
    assert review["imported"] == {"spotbugs": 2} and review["groups"][0]["total"] == 2


def test_coverage_gaps_reads_jacoco(local_tools: dict[str, Any], tmp_path: Path) -> None:
    missing = local_tools["coverage_gaps"]()
    assert missing["error"].startswith("no JaCoCo report at build/reports/jacoco/test/jacocoTestReport.xml")
    write_reports(tmp_path)
    found = local_tools["coverage_gaps"]()
    assert found["coverage"] == {"lines": 5, "covered": 2, "missed": 3, "percent": 40}
    assert found["count"] == 2 and found["uncoveredMethods"] == 1
    assert found["items"][0]["name"] == "A" and found["items"][1]["name"] == "A.run"
    assert found["report"] == "build/reports/jacoco/test/jacocoTestReport.xml"
    assert local_tools["coverage_gaps"](paths=["other"])["count"] == 0
    (tmp_path / "build" / "reports" / "jacoco" / "test" / "jacocoTestReport.xml").write_text("<x/>", encoding="utf-8")
    assert "not a JaCoCo report" in local_tools["coverage_gaps"]()["error"]


def test_review_changes_lists_uncovered_methods_in_the_change(
    local_tools: dict[str, Any], engine: dict[str, Any], tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    write_reports(tmp_path)
    engine["responses"]["groovyscope/check"] = {"diagnostics": {}, "summary": {}}
    engine["responses"]["groovyscope/documentSymbols"] = {"symbols": [], "lines": 10}
    engine["responses"]["groovyscope/findCode"] = {"matches": [], "count": 0}
    change = {
        "base": "main",
        "mode": "branch",
        "files": [{"path": "src/A.groovy", "status": "modified", "ranges": [[3, 6]]}],
    }
    monkeypatch.setattr(mcp_server.changes, "changed_files", lambda *_a, **_k: change)
    result = local_tools["review_changes"]()
    assert result["uncoveredCount"] == 1 and result["uncovered"][0]["name"] == "A.run"
    assert result["counts"]["blocking"] == 1 and result["blocking"][0]["code"] == "NP_NULL"
    assert result["imported"] == {"spotbugs": 1}
    assert result["verdict"].startswith("request changes")
