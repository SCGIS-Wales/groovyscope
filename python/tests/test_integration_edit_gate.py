"""The edit gate against the real engine: the preToolUse hook previews a write, edit_symbol plans and applies one."""

from __future__ import annotations

import json
import shutil
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


FIXTURE = Path(__file__).resolve().parent / "fixtures" / "edit-gate"


@pytest.mark.skipif(not engine_available(), reason="needs a JDK and an engine JAR (bundled or GROOVYSCOPE_JAR)")
def test_writing_a_copy_of_existing_code_is_held_back(
    tmp_path: Path, short_dir: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    workspace = tmp_path / "ws"
    shutil.copytree(FIXTURE, workspace)
    copied = workspace / "src" / "main" / "groovy" / "demo" / "Copied.groovy"
    text = copied.read_text(encoding="utf-8")
    copied.unlink()  # the agent is about to write it
    (workspace / ".groovyscope.yaml").write_text("hooks:\n  preWrite:\n    mode: block\n", encoding="utf-8")
    monkeypatch.setenv(daemon.SOCKET_DIR_ENV, str(short_dir))
    monkeypatch.setenv(daemon.CACHE_DIR_ENV, str(tmp_path / "cache"))
    monkeypatch.setenv("GROOVYSCOPE_DAEMON__IDLETIMEOUTMINUTES", "1")
    runner = CliRunner()
    obj = {"config_path": None, "root": workspace.resolve(), "log_level": "WARNING", "log_file": None}
    try:
        socket_file, _ = common.daemon_socket_for(obj)
        payload = {
            "hook_event_name": "PreToolUse",
            "tool_name": "write",
            "tool_input": {"path": str(copied), "file_text": text},
        }
        blocked = runner.invoke(cli.main, ["--root", str(workspace), "hook"], input=json.dumps(payload))
        assert blocked.exit_code == 2, blocked.output
        assert "[GS080] repeat demo.Strings.slug" in blocked.stderr
        tools = common.workspace_tools(obj, socket_file, load_config(obj["root"], None, {}))
        found = tools["suggest_reuse"](text=text.split("class Copied {", 1)[1].rsplit("}", 1)[0])
        assert found["matches"][0]["symbol"] == "demo.Strings.slug" and found["matches"][0]["score"] > 0.8
    finally:
        runner.invoke(cli.main, ["--root", str(workspace), "daemon", "stop"])


@pytest.mark.skipif(not engine_available(), reason="needs a JDK and an engine JAR (bundled or GROOVYSCOPE_JAR)")
def test_a_type_error_under_compile_static_is_caught_before_the_write(
    tmp_path: Path, short_dir: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    workspace = tmp_path / "ws"
    source = workspace / "src" / "main" / "groovy" / "demo" / "Counter.groovy"
    source.parent.mkdir(parents=True)
    text = (
        "package demo\n\nimport groovy.transform.CompileStatic\n\n"
        "@CompileStatic\nclass Counter {\n    int count() {\n        return 1\n    }\n}\n"
    )
    source.write_text(text, encoding="utf-8")
    (workspace / ".groovyscope.yaml").write_text("hooks:\n  preWrite:\n    mode: block\n", encoding="utf-8")
    monkeypatch.setenv(daemon.SOCKET_DIR_ENV, str(short_dir))
    monkeypatch.setenv(daemon.CACHE_DIR_ENV, str(tmp_path / "cache"))
    monkeypatch.setenv("GROOVYSCOPE_DAEMON__IDLETIMEOUTMINUTES", "1")
    runner = CliRunner()
    obj = {"config_path": None, "root": workspace.resolve(), "log_level": "WARNING", "log_file": None}
    try:
        common.daemon_socket_for(obj)
        broken = {"path": str(source), "command": "str_replace", "old_str": "return 1", "new_str": "return 'one'"}
        payload = {"hook_event_name": "PreToolUse", "tool_name": "write", "tool_input": broken}
        blocked = runner.invoke(cli.main, ["--root", str(workspace), "hook"], input=json.dumps(payload))
        assert blocked.exit_code == 2, blocked.output
        assert "BLOCKED" in blocked.stderr and "String" in blocked.stderr
    finally:
        runner.invoke(cli.main, ["--root", str(workspace), "daemon", "stop"])


PRICES = """package demo

import groovy.transform.CompileStatic

@CompileStatic
class Prices {
    static int total(int quantity, int price) {
        int gross = quantity * price
        int rounded = gross - gross % 5
        return rounded
    }
}
"""
SHOP = """package demo

import groovy.transform.CompileStatic

@CompileStatic
class Shop {
    int one() { Prices.total(1, 2) }
    int two() { Prices.total(3, 4) }
    int three() { Prices.total(5, 6) }
}
"""


@pytest.mark.skipif(not engine_available(), reason="needs a JDK and an engine JAR (bundled or GROOVYSCOPE_JAR)")
def test_change_signature_and_extract_method_through_the_daemon(
    tmp_path: Path, short_dir: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    workspace = tmp_path / "ws"
    folder = workspace / "src" / "main" / "groovy" / "demo"
    folder.mkdir(parents=True)
    (folder / "Prices.groovy").write_text(PRICES, encoding="utf-8")
    (folder / "Shop.groovy").write_text(SHOP, encoding="utf-8")
    monkeypatch.setenv(daemon.SOCKET_DIR_ENV, str(short_dir))
    monkeypatch.setenv(daemon.CACHE_DIR_ENV, str(tmp_path / "cache"))
    monkeypatch.setenv("GROOVYSCOPE_DAEMON__IDLETIMEOUTMINUTES", "1")
    runner = CliRunner()
    obj = {"config_path": None, "root": workspace.resolve(), "log_level": "WARNING", "log_file": None}
    try:
        socket_file, _ = common.daemon_socket_for(obj)
        tools = common.workspace_tools(obj, socket_file, load_config(obj["root"], None, {}))
        parameters = [
            {"name": "quantity", "type": "int"},
            {"name": "price", "type": "int"},
            {"name": "discount", "type": "int", "default": "0"},
        ]
        changed = tools["change_signature"](
            "src/main/groovy/demo/Prices.groovy", "Prices.total", parameters, apply=True
        )
        assert changed["applied"] is True and changed["callSites"] == 3, changed
        shop = (folder / "Shop.groovy").read_text(encoding="utf-8")
        assert shop.count(", 0)") == 3 and "Prices.total(1, 2, 0)" in shop
        extracted = tools["extract_method"]("src/main/groovy/demo/Prices.groovy", 8, 9, "rounded", apply=True)
        assert extracted["applied"] is True and "verified" in extracted, extracted
        prices = (folder / "Prices.groovy").read_text(encoding="utf-8")
        assert "rounded(" in prices and extracted["static"] is True
        checked = runner.invoke(cli.main, ["--root", str(workspace), "check", "--include", "compile"])
        assert checked.exit_code == 0, checked.output
    finally:
        runner.invoke(cli.main, ["--root", str(workspace), "daemon", "stop"])


@pytest.mark.skipif(not engine_available(), reason="needs a JDK and an engine JAR (bundled or GROOVYSCOPE_JAR)")
def test_class_design_finds_two_jobs_in_one_class(
    tmp_path: Path, short_dir: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    workspace = tmp_path / "ws"
    folder = workspace / "src" / "main" / "groovy" / "demo"
    folder.mkdir(parents=True)
    (folder / "Desk.groovy").write_text(
        "package demo\n\nclass Desk {\n    List<String> notes = []\n    int coins = 0\n\n"
        "    void note(String text) { notes << text }\n    int noteCount() { notes.size() }\n"
        "    void pay(int amount) { coins += amount }\n    int balance() { coins }\n}\n",
        encoding="utf-8",
    )
    monkeypatch.setenv(daemon.SOCKET_DIR_ENV, str(short_dir))
    monkeypatch.setenv(daemon.CACHE_DIR_ENV, str(tmp_path / "cache"))
    monkeypatch.setenv("GROOVYSCOPE_DAEMON__IDLETIMEOUTMINUTES", "1")
    runner = CliRunner()
    obj = {"config_path": None, "root": workspace.resolve(), "log_level": "WARNING", "log_file": None}
    try:
        socket_file, _ = common.daemon_socket_for(obj)
        tools = common.workspace_tools(obj, socket_file, load_config(obj["root"], None, {}))
        (desk,) = tools["class_design"](symbol="demo.Desk")["items"]
        assert desk["cohesion"]["lcom4"] == 2, desk
        groups = sorted(sorted(group["methods"]) for group in desk["cohesion"]["groups"])
        assert groups == [["balance", "pay"], ["note", "noteCount"]]
    finally:
        runner.invoke(cli.main, ["--root", str(workspace), "daemon", "stop"])
