"""The edit gate against the real engine: the preToolUse hook previews a write, edit_symbol plans and applies one."""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from click.testing import CliRunner

from groovyscope import cli, daemon
from groovyscope.cli import common
from groovyscope.config import load_config
from tests.test_integration_lsp import engine_available

pytestmark = pytest.mark.integration

SHAPE = """package demo

class Shape {
    int sides = 4

    /** The number of corners. */
    int corners() {
        return sides
    }
}
"""


@pytest.mark.skipif(not engine_available(), reason="needs a JDK and an engine JAR (bundled or GROOVYSCOPE_JAR)")
def test_the_gate_and_edit_symbol_through_the_daemon(
    tmp_path: Path, short_dir: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    workspace = tmp_path / "ws"
    source = workspace / "src" / "main" / "groovy" / "demo" / "Shape.groovy"
    source.parent.mkdir(parents=True)
    source.write_text(SHAPE, encoding="utf-8")
    monkeypatch.setenv(daemon.SOCKET_DIR_ENV, str(short_dir))
    monkeypatch.setenv(daemon.CACHE_DIR_ENV, str(tmp_path / "cache"))
    monkeypatch.setenv("GROOVYSCOPE_DAEMON__IDLETIMEOUTMINUTES", "1")
    (workspace / ".groovyscope.yaml").write_text("hooks:\n  preWrite:\n    mode: block\n", encoding="utf-8")
    runner = CliRunner()
    obj = {"config_path": None, "root": workspace.resolve(), "log_level": "WARNING", "log_file": None}
    try:
        socket_file, _ = common.daemon_socket_for(obj)  # the LSP session would start it; the hook never does
        broken = {"path": str(source), "command": "str_replace", "old_str": "return sides", "new_str": "new Gone()"}
        payload = {"hook_event_name": "PreToolUse", "tool_name": "write", "tool_input": broken}
        blocked = runner.invoke(cli.main, ["--root", str(workspace), "hook"], input=json.dumps(payload))
        assert blocked.exit_code == 2, blocked.output
        assert "BLOCKED" in blocked.stderr and "Gone" in blocked.stderr
        fine = {**broken, "new_str": "return sides * 2"}
        payload["tool_input"] = fine
        passed = runner.invoke(cli.main, ["--root", str(workspace), "hook"], input=json.dumps(payload))
        assert passed.exit_code == 0, passed.output

        effective = load_config(obj["root"], None, {})
        edit = common.workspace_tools(obj, socket_file, effective)["edit_symbol"]
        planned = edit("src/main/groovy/demo/Shape.groovy", "Shape.corners", "replace_body", text="sides * 2")
        assert planned["gate"]["decision"] == "pass", planned
        assert planned["plan"]["old_str"] in SHAPE and "sides * 2" in planned["plan"]["new_str"]
        assert source.read_text(encoding="utf-8") == SHAPE
        payload["tool_input"] = {
            "path": str(source),
            "command": "str_replace",
            "old_str": planned["plan"]["old_str"],
            "new_str": planned["plan"]["new_str"],
        }
        trusted = runner.invoke(cli.main, ["--root", str(workspace), "hook"], input=json.dumps(payload))
        assert trusted.exit_code == 0 and "planned write to" in trusted.output

        applied = edit(
            "src/main/groovy/demo/Shape.groovy", "Shape.corners", "replace", text="int corners() { sides }", apply=True
        )
        assert applied["edited"] is True, applied
        text = source.read_text(encoding="utf-8")
        assert "    int corners() { sides }\n" in text and "The number of corners" not in text
    finally:
        runner.invoke(cli.main, ["--root", str(workspace), "daemon", "stop"])


@pytest.mark.skipif(not engine_available(), reason="needs a JDK and an engine JAR (bundled or GROOVYSCOPE_JAR)")
def test_integration_sources_are_compiled_with_the_change(
    tmp_path: Path, short_dir: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    workspace = tmp_path / "ws"
    main = workspace / "src" / "main" / "groovy" / "demo" / "Shape.groovy"
    it = workspace / "src" / "it" / "groovy" / "demo" / "ShapeIT.groovy"
    for path in (main, it):
        path.parent.mkdir(parents=True)
    main.write_text("package demo\n\nclass Shape {\n    int sides = 4\n}\n", encoding="utf-8")
    it.write_text("package demo\n\nclass ShapeIT {\n    def sides() { new Shape().sides }\n}\n", encoding="utf-8")
    monkeypatch.setenv(daemon.SOCKET_DIR_ENV, str(short_dir))
    monkeypatch.setenv(daemon.CACHE_DIR_ENV, str(tmp_path / "cache"))
    monkeypatch.setenv("GROOVYSCOPE_DAEMON__IDLETIMEOUTMINUTES", "1")
    runner = CliRunner()
    obj = {"config_path": None, "root": workspace.resolve(), "log_level": "WARNING", "log_file": None}
    try:
        common.daemon_socket_for(obj)
        main.write_text("package demo\n\nclass Polygon {\n    int sides = 4\n}\n", encoding="utf-8")  # renamed
        written = {"hook_event_name": "PostToolUse", "tool_name": "write", "tool_input": {"path": str(it)}}
        after = runner.invoke(cli.main, ["--root", str(workspace), "hook"], input=json.dumps(written))
        assert after.exit_code == 0, after.output
        assert "src/it/groovy/demo/ShapeIT.groovy" in after.output and "Shape" in after.output
        checked = runner.invoke(cli.main, ["--root", str(workspace), "check", "--include", "compile"])
        assert checked.exit_code == 1 and "ShapeIT.groovy" in checked.output
    finally:
        runner.invoke(cli.main, ["--root", str(workspace), "daemon", "stop"])
