"""The cost of the tool surface: every schema is in the agent's context on every session, so it has a budget."""

from __future__ import annotations

import asyncio
import json
import re
from importlib import resources
from pathlib import Path
from typing import Any

from groovyscope import mcp_server, tools_catalog

# Before Stage 16 the 27 schemas came to 21,768 bytes. The budget leaves room for a tool or two, not for a
# return of the boilerplate; raise it only with the numbers in docs/roadmap.md. Stage 22 (merge conflicts): the
# 29 tools came to 13,986 bytes, the three merge tools add about 1,000. Stage 23 (unused code): find_unused adds
# 452, to 15,455. Stage 24 (edit gate): edit_symbol adds 715, to 16,170; the edit intelligence plan lets the
# later phases' tools take it towards 18,500. Stage 27 (reuse): suggest_reuse adds 553, to 16,723. Stage 28 (lean
# tests): test_plan adds 487.
SCHEMA_BUDGET_BYTES = 17_250
DESCRIPTION_LIMIT = 230
INSTRUCTIONS_LIMIT = 700


def listed_tools() -> list[dict[str, Any]]:
    server = mcp_server.build_server(Path("/w"), lambda _m, _p: {}, {"maxResults": 50, "maxBytes": 16000}, {})
    return [tool.model_dump(by_alias=True, exclude_none=True) for tool in asyncio.run(server.list_tools())]


def test_the_tool_surface_stays_within_its_budget() -> None:
    tools = listed_tools()
    size = sum(len(json.dumps(tool, separators=(",", ":"))) for tool in tools)
    assert len(tools) == len(tools_catalog.TOOL_INFO)
    assert size <= SCHEMA_BUDGET_BYTES, f"{size} bytes of tool schemas, budget {SCHEMA_BUDGET_BYTES}"
    assert len(mcp_server.INSTRUCTIONS) <= INSTRUCTIONS_LIMIT


def test_every_tool_has_a_short_catalogue_description() -> None:
    tools = {tool["name"]: tool for tool in listed_tools()}
    assert set(tools_catalog.DESCRIPTIONS) == set(tools_catalog.TOOL_INFO) == set(tools)
    for name, tool in tools.items():
        assert tool["description"] == tools_catalog.DESCRIPTIONS[name]
        assert len(tool["description"]) <= DESCRIPTION_LIMIT, name
        assert "\n" not in tool["description"], name
    assert tools_catalog.description("nope", " fallback ") == "fallback"
    assert tools_catalog.description("nope") == "nope"


def test_schemas_carry_no_generated_boilerplate() -> None:
    for tool in listed_tools():
        schema = tool["inputSchema"]
        assert "title" not in schema
        assert "title" not in tool.get("annotations", {})
        for name, prop in schema.get("properties", {}).items():
            assert "title" not in prop, (tool["name"], name)
            assert "anyOf" not in prop or {"type": "null"} not in prop["anyOf"], (tool["name"], name)
            assert prop.get("default", "set") is not None, (tool["name"], name)


def test_compact_schema_keeps_meaning() -> None:
    schema = {
        "title": "xArguments",
        "type": "object",
        "required": ["kind"],
        "properties": {
            "kind": {"title": "Kind", "type": "string"},
            "paths": {
                "anyOf": [{"items": {"type": "string"}, "type": "array"}, {"type": "null"}],
                "default": None,
                "title": "Paths",
            },
            "cursor": {"default": 0, "title": "Cursor", "type": "integer"},
            "either": {"anyOf": [{"type": "string"}, {"type": "integer"}], "title": "Either"},
            "title": {"title": "Title", "type": "string"},
        },
    }
    assert mcp_server.compact_schema(schema) == {
        "type": "object",
        "required": ["kind"],
        "properties": {
            "kind": {"type": "string"},
            "paths": {"items": {"type": "string"}, "type": ["array", "null"]},
            "cursor": {"default": 0, "type": "integer"},
            "either": {"anyOf": [{"type": "string"}, {"type": "integer"}]},
            "title": {"type": "string"},
        },
    }


def test_a_compacted_tool_still_accepts_null_and_omitted_arguments() -> None:
    seen: list[tuple[str, dict[str, Any]]] = []

    def call(method: str, params: dict[str, Any]) -> Any:
        seen.append((method, params))
        return {"symbols": []}

    server = mcp_server.build_server(Path("/w"), call, {"maxResults": 5, "maxBytes": 16000}, {})
    asyncio.run(server.call_tool("find_symbol", {"query": "A", "kind": None}))
    asyncio.run(server.call_tool("find_symbol", {"query": "B"}))
    assert [params["query"] for _, params in seen] == ["A", "B"]


def test_fit_cuts_any_answer_to_the_byte_cap() -> None:
    small = {"items": [1, 2, 3]}
    assert mcp_server.fit(small, 1000) is small
    assert mcp_server.fit(small, 0) is small
    big = {"found": True, "items": [{"path": f"src/F{i}.groovy", "line": i} for i in range(400)], "tail": "x"}
    cut = mcp_server.fit(big, 2000)
    assert len(mcp_server.compact_json(cut).encode("utf-8")) <= 2000
    assert cut["found"] is True and cut["tail"] == "x"
    assert 0 < len(cut["items"]) < 400
    assert cut["truncated"]["items"] == 400 - len(cut["items"])
    assert "output.maxBytes" in cut["note"]
    assert len(big["items"]) == 400  # the original is untouched
    nested = {"reports": [{"callers": [{"n": i} for i in range(300)]}]}
    assert "reports.callers" in mcp_server.fit(nested, 600)["truncated"]
    prose = {"markdown": "y" * 5000}
    clipped = mcp_server.fit(prose, 1200)
    assert len(clipped["markdown"]) < 1200 and clipped["markdown"].endswith(" ...")


def test_symbol_context_answers_in_one_call(tools: dict[str, Any], engine: dict[str, Any]) -> None:
    engine["responses"]["groovyscope/definition"] = {
        "symbol": {"name": "greet", "kind": "method", "location": {"uri": "file:///ws/src/A.groovy", "line": 3}},
        "confidence": "STATIC",
    }
    engine["responses"]["groovyscope/hover"] = {"markdown": "String greet(String name)"}
    engine["responses"]["groovyscope/references"] = {
        "references": [{"confidence": "STATIC"}, {"confidence": "STATIC"}, {"confidence": "DYNAMIC_UNKNOWN"}, {}]
    }
    answer = tools["symbol_context"]("src/A.groovy", 4, 6)
    assert answer["found"] is True and answer["confidence"] == "STATIC"
    assert answer["hover"] == "String greet(String name)"
    assert answer["references"] == {"total": 4, "byConfidence": {"STATIC": 2, "DYNAMIC_UNKNOWN": 1, "UNKNOWN": 1}}
    methods = [method for method, _ in engine["calls"]]
    assert methods == ["groovyscope/definition", "groovyscope/hover", "groovyscope/references"]
    assert engine["calls"][-1][1]["includeDeclaration"] is False
    engine["responses"].clear()
    nothing = tools["symbol_context"]("src/A.groovy", 1, 1)
    assert nothing["found"] is False and nothing["hover"] is None and nothing["references"]["total"] == 0


def test_the_agent_texts_name_no_tool_count_and_both_prompt_syntaxes() -> None:
    """A fixed count goes stale the day a tool is added (0.18.0 said 27 with 28 served); prompts are `/name` in
    Kiro's terminal UI and `@name` in the classic chat, so the texts must name both."""
    templates = resources.files("groovyscope") / "_templates" / "kiro"
    agent = json.loads((templates / "agents" / "groovy.json").read_text(encoding="utf-8"))
    assert not re.search(r"\d+ tools", agent["welcomeMessage"])
    steering = (templates / "steering" / "groovyscope.md").read_text(encoding="utf-8")
    assert "`/groovy-review`" in steering and "`@groovy-review`" in steering
