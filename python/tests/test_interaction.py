"""Confirmations and progress from inside a tool: the interaction module and its use by the writing tools."""

from __future__ import annotations

import asyncio
import os
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from groovyscope import classpath, interaction, mcp_server
from tests.test_mcp_understanding import GRAPH

ELICITING = SimpleNamespace(elicitation=SimpleNamespace())


def context(*, capabilities: Any = ELICITING, answer: Any = None) -> Any:
    """A stand-in for the SDK's request context: what it declares, what the user answers, what it was told."""
    told: list[tuple[float, float | None, str]] = []
    asked: list[str] = []

    async def elicit(message: str, _schema: Any) -> Any:
        asked.append(message)
        return answer

    async def report_progress(done: float, total: float | None = None, message: str | None = None) -> None:
        told.append((done, total, message or ""))

    return SimpleNamespace(
        client_capabilities=capabilities, elicit=elicit, report_progress=report_progress, told=told, asked=asked
    )


def direct(function: Any, *args: Any) -> Any:
    """``anyio.from_thread.run`` for a test that is not on a worker thread."""
    return asyncio.run(function(*args))


def test_bound_contexts_nest_per_thread() -> None:
    assert interaction.current() is None
    with interaction.bound("outer"):
        with interaction.bound("inner"):
            assert interaction.current() == "inner"
        assert interaction.current() == "outer"
        seen: list[Any] = []
        worker = threading.Thread(target=lambda: seen.append(interaction.current()))
        worker.start()
        worker.join()
        assert seen == [None]
    assert interaction.current() is None


def test_confirm_answers_for_every_client_and_every_reply() -> None:
    assert interaction.confirm("Write?") == interaction.UNSUPPORTED  # no tool call in progress
    with interaction.bound(context(capabilities=None)):
        assert interaction.confirm("Write?", run=direct) == interaction.UNSUPPORTED
    with interaction.bound(context(capabilities=SimpleNamespace(elicitation=None))):
        assert interaction.confirm("Write?", run=direct) == interaction.UNSUPPORTED
    replies = {
        interaction.ACCEPTED: SimpleNamespace(action="accept", data=SimpleNamespace(apply=True)),
        interaction.DECLINED: SimpleNamespace(action="decline", data=None),
        interaction.CANCELLED: SimpleNamespace(action="cancel", data=None),
    }
    for expected, reply in replies.items():
        ctx = context(answer=reply)
        with interaction.bound(ctx):
            assert interaction.confirm("Write 2 files?", run=direct) == expected
        assert ctx.asked == ["Write 2 files?"]
    unticked = context(answer=SimpleNamespace(action="accept", data=SimpleNamespace(apply=False)))
    with interaction.bound(unticked):
        assert interaction.confirm("Write?", run=direct) == interaction.DECLINED
        assert interaction.confirm("Write?", policy=interaction.POLICY_NEVER, run=direct) == interaction.UNSUPPORTED

    def broken(*_a: Any) -> Any:
        msg = "client went away"
        raise RuntimeError(msg)

    with interaction.bound(context()):
        assert interaction.confirm("Write?", run=broken) == interaction.CANCELLED


def test_progress_is_sent_only_inside_a_tool_call_and_never_fails() -> None:
    calls: list[Any] = []
    interaction.progress(1, None, "nobody listens", run=lambda *a: calls.append(a))
    assert calls == []
    ctx = context()
    with interaction.bound(ctx):
        interaction.progress(5, 10, "half way", run=direct)
        interaction.progress(6, None, "ignored", run=lambda *_a: (_ for _ in ()).throw(RuntimeError("gone")))
    assert ctx.told == [(5, 10, "half way")]


def test_write_question_lists_files_and_counts_the_rest() -> None:
    files = [(f"src/F{i}.groovy", i) for i in range(12)]
    text = interaction.write_question("Format 12 file(s)", files, limit=10)
    lines = text.splitlines()
    assert lines[0] == "Format 12 file(s)?" and lines[1] == "- src/F0.groovy: 0 line(s)"
    assert lines[-1] == "- and 2 more file(s)" and len(lines) == 12


def format_edit(target: Path) -> dict[str, Any]:
    return {
        "edits": {
            target.as_uri(): [
                {"range": {"start": {"line": 0, "character": 0}, "end": {"line": 1, "character": 0}}, "newText": "a\n"}
            ]
        },
        "checked": 1,
    }


@pytest.mark.parametrize(
    ("decision", "written"),
    [
        (interaction.ACCEPTED, True),
        (interaction.DECLINED, False),
        (interaction.CANCELLED, False),
        (interaction.UNSUPPORTED, True),
    ],
)
def test_format_fix_and_rename_write_only_what_the_user_accepts(
    local_tools: dict[str, Any],
    engine: dict[str, Any],
    tmp_path: Path,
    *,
    monkeypatch: pytest.MonkeyPatch,
    decision: str,
    written: bool,
) -> None:
    asked: list[str] = []
    monkeypatch.setattr(interaction, "confirm", lambda message, **_k: asked.append(message) or decision)
    target = tmp_path / "Messy.groovy"
    target.write_text("a  \n", encoding="utf-8")
    engine["responses"]["groovyscope/format"] = format_edit(target)
    answer = local_tools["format"](paths=["Messy.groovy"], apply=True)
    assert asked == ["Format 1 file(s)?\n- Messy.groovy: 1 line(s)"]
    assert (target.read_text(encoding="utf-8") == "a\n") is written
    assert answer["files"][0]["applied"] is written
    if decision == interaction.UNSUPPORTED:
        assert "confirmation" not in answer
    else:
        assert answer["confirmation"] == decision
    assert ("note" in answer) is (not written)

    target.write_text("a  \n", encoding="utf-8")
    engine["responses"]["groovyscope/fix"] = {"edits": format_edit(target)["edits"], "applied": {}}
    fixed = local_tools["fix"](apply=True)
    assert asked[-1].startswith("Apply safe fixes to 1 file(s)?")
    assert (target.read_text(encoding="utf-8") == "a\n") is written and fixed["files"][0]["applied"] is written

    target.write_text("a  \n", encoding="utf-8")
    engine["responses"]["groovyscope/rename"] = {"edits": format_edit(target)["edits"]}
    engine["responses"]["groovyscope/check"] = {"diagnostics": {}}
    renamed = local_tools["rename"]("Messy.groovy", 1, 1, "b", apply=True)
    assert asked[-1].startswith("Rename to b in 1 file(s)?")
    assert renamed["renamed"] is written and (target.read_text(encoding="utf-8") == "a\n") is written


def test_a_dry_run_never_asks(
    local_tools: dict[str, Any], engine: dict[str, Any], tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(interaction, "confirm", lambda *_a, **_k: pytest.fail("a dry run must not ask"))
    target = tmp_path / "Messy.groovy"
    target.write_text("a  \n", encoding="utf-8")
    engine["responses"]["groovyscope/format"] = format_edit(target)
    assert local_tools["format"](paths=["Messy.groovy"])["files"][0]["applied"] is False


def test_the_sdk_hands_the_request_context_to_the_tool_and_hides_it_from_the_schema(
    engine: dict[str, Any], tmp_path: Path
) -> None:
    target = tmp_path / "Messy.groovy"
    target.write_text("a  \n", encoding="utf-8")

    def call(method: str, params: dict[str, Any]) -> Any:
        engine["calls"].append((method, params))
        return format_edit(target) if method == "groovyscope/format" else {}

    server = mcp_server.build_server(tmp_path, call, {"maxResults": 5, "maxBytes": 16000}, {})
    schemas = {tool.name: tool.input_schema for tool in asyncio.run(server.list_tools())}
    assert all(mcp_server.CONTEXT_PARAMETER not in schema.get("properties", {}) for schema in schemas.values())
    ctx = context(answer=SimpleNamespace(action="decline", data=None))
    result = asyncio.run(server.call_tool("format", {"apply": True}, ctx))
    text = result.content[0].text
    assert '"confirmation":"declined"' in text and target.read_text(encoding="utf-8") == "a  \n"
    assert ctx.asked and ctx.asked[0].startswith("Format 1 file(s)?")


def test_waiting_calls_send_progress_notes(monkeypatch: pytest.MonkeyPatch) -> None:
    notes: list[str] = []
    monkeypatch.setattr(interaction, "progress", lambda _d, _t, message, **_k: notes.append(message))

    def slow(method: str, params: dict[str, Any]) -> Any:
        time.sleep(0.35)
        return {"method": method, "params": params}

    call = mcp_server.single_flight(slow, budget=10, max_in_flight=2, progress_every=0.1)
    assert call("groovyscope/check", {"a": 1}) == {"method": "groovyscope/check", "params": {"a": 1}}
    assert notes and all("groovyscope/check: the engine is still working" in note for note in notes)


def test_refresh_classpath_asks_first_reads_trust_now_and_holds_the_lock(
    tmp_path: Path, engine: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    (tmp_path / "build.gradle").write_text("plugins {}\n")
    target = tmp_path / ".groovyscope" / "classpath.txt"

    def tools(**settings: Any) -> dict[str, Any]:
        def call(method: str, params: dict[str, Any]) -> Any:
            return engine["responses"].get(method, {})

        output = {"maxResults": 3, "maxBytes": 16000, "classpath": {"file": str(target), **settings}}
        return mcp_server.build_tools(tmp_path, call, output, {})

    revoked = tools(trusted=True, isTrusted=lambda: False)
    assert "groovyscope trust" in revoked["refresh_classpath"]()["error"]

    exported: list[Any] = []
    monkeypatch.setattr(
        mcp_server.classpath_export,
        "export",
        lambda *_a, **_k: (
            exported.append(1) or {"tool": "gradle", "command": ["./gradlew"], "paths": [], "missing": []}
        ),
    )
    monkeypatch.setattr(interaction, "confirm", lambda *_a, **_k: interaction.DECLINED)
    said_no = tools(trusted=True)["refresh_classpath"]()
    assert said_no == {
        "refreshed": False,
        "confirmation": "declined",
        "reason": "nothing was run: the user said no",
    }
    assert exported == []

    monkeypatch.setattr(interaction, "confirm", lambda *_a, **_k: interaction.ACCEPTED)
    with classpath.exclusive(target):
        busy = tools(trusted=True)["refresh_classpath"]()
    assert "already running" in busy["error"] and exported == []
    done = tools(trusted=True, isTrusted=lambda: True)["refresh_classpath"]()
    assert done["confirmation"] == "accepted" and done["entries"] == 0 and exported == [1]


def test_the_build_runs_in_its_own_process_group_and_is_stopped_whole(tmp_path: Path) -> None:
    code, output = classpath.default_runner([sys.executable, "-c", "print('hi')"], tmp_path, dict(os.environ), 30)
    assert code == 0 and output.strip() == "hi"
    heard: list[float] = []
    slow = [sys.executable, "-c", "import time; time.sleep(30)"]
    started = time.monotonic()
    code, output = classpath.default_runner(slow, tmp_path, dict(os.environ), 1, on_wait=heard.append, tick=0.2)
    assert code == -1 and "did not finish within 1 s; the build was stopped" in output
    assert heard and time.monotonic() - started < 15
    missing = classpath.default_runner(["/no/such/tool"], tmp_path, dict(os.environ), 5)
    assert missing[0] == -1

    family = subprocess.Popen(["/bin/sh", "-c", "sleep 30 & sleep 30"], start_new_session=True)
    classpath.stop_group(family)
    for _ in range(50):
        try:
            os.killpg(family.pid, 0)
        except ProcessLookupError:
            break
        time.sleep(0.1)
    else:
        os.killpg(family.pid, signal.SIGKILL)
        pytest.fail("the build's process group survived")


def test_a_declined_drawio_write_leaves_no_file(
    local_tools: dict[str, Any], engine: dict[str, Any], tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine["responses"]["groovyscope/graph"] = GRAPH
    monkeypatch.setattr(interaction, "confirm", lambda *_a, **_k: interaction.DECLINED)
    answer = local_tools["relationship_graph"](drawio_path="docs/classes.drawio")
    assert answer["written"] is None and answer["confirmation"] == "declined"
    assert not (tmp_path / "docs" / "classes.drawio").exists()


def test_the_practices_catalogue_is_a_resource() -> None:
    index = mcp_server.practices_index()
    assert index.startswith("# Groovy practices") and "| GS001 |" in index and "groovyscope://practices/<code>" in index
    page = mcp_server.practice_page("gs001")
    assert page.startswith("# GS001: Integer division") and "**Fix:**" in page and "intdiv" in page
    assert "Not a groovyscope practice code" in mcp_server.practice_page("Nope")
    server = mcp_server.build_server(Path("/w"), lambda _m, _p: {}, {"maxResults": 5, "maxBytes": 16000}, {})
    listed = [str(resource.uri) for resource in asyncio.run(server.list_resources())]
    assert mcp_server.PRACTICES_URI in listed
    templates = [str(t.uri_template) for t in asyncio.run(server.list_resource_templates())]
    assert mcp_server.PRACTICES_URI + "/{code}" in templates
    read = asyncio.run(server.read_resource(mcp_server.PRACTICES_URI + "/GS001"))
    assert "Integer division" in str(read)
