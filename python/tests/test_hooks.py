"""Kiro hook handling: payload parsing, which writes count, the after-write block and the stop gate."""

from __future__ import annotations

import json
from pathlib import Path

from groovyscope import hooks


def test_payload_and_event_names() -> None:
    assert hooks.parse_payload("") == {}
    assert hooks.parse_payload("not json") == {}
    assert hooks.parse_payload("[1]") == {}
    assert hooks.parse_payload('{"hook_event_name": "PostToolUse"}') == {"hook_event_name": "PostToolUse"}
    assert hooks.event_name({"hook_event_name": "PostToolUse"}) == "postToolUse"
    assert hooks.event_name({"hook_event_name": "post_tool_use"}) == "postToolUse"
    assert hooks.event_name({"event": "Stop"}) == "stop"
    assert hooks.event_name({"hook_event_name": "agentSpawn"}) == "sessionStart"
    assert hooks.event_name({}, "stop") == "stop"
    assert hooks.event_name({"hook_event_name": "Weird"}) == "Weird"


def test_settings_fill_the_defaults() -> None:
    found = hooks.settings({})
    assert found["afterWrite"] is True and found["stopGate"] is False
    assert found["afterWriteInclude"] == ["compile", "lint", "practices"]
    assert found["afterWriteMaxFindings"] == 12 and found["timeoutSeconds"] == 45
    tuned = hooks.settings({"hooks": {"stopGate": True, "timeoutSeconds": 5, "afterWriteInclude": ["compile"]}})
    assert tuned["stopGate"] is True and tuned["timeoutSeconds"] == 5 and tuned["afterWriteInclude"] == ["compile"]


def test_written_paths_keep_groovy_files_inside_the_workspace(tmp_path: Path) -> None:
    src = tmp_path / "src" / "main" / "groovy"
    src.mkdir(parents=True)
    groovy = src / "A.groovy"
    groovy.write_text("class A {}")
    (tmp_path / "Jenkinsfile").write_text("node {}")
    (tmp_path / "notes.md").write_text("x")
    outside = tmp_path.parent / "Outside.groovy"
    outside.write_text("class Outside {}")
    payload = {"tool_name": "write", "tool_input": {"path": "src/main/groovy/A.groovy", "command": "create"}}
    assert hooks.written_paths(payload, tmp_path) == [groovy.resolve()]
    absolute = {"tool_name": "@builtin/fs_write", "tool_input": {"file_path": str(groovy)}}
    assert hooks.written_paths(absolute, tmp_path) == [groovy.resolve()]
    many = {"tool_name": "write", "tool_input": {"paths": ["Jenkinsfile", "notes.md", str(outside), "missing.groovy"]}}
    assert hooks.written_paths(many, tmp_path) == [(tmp_path / "Jenkinsfile").resolve()]
    assert hooks.written_paths({"tool_name": "read", "tool_input": {"path": str(groovy)}}, tmp_path) == []
    assert hooks.written_paths({"tool_name": "delete_file", "tool_input": {"path": str(groovy)}}, tmp_path) == []
    assert hooks.written_paths({"tool_name": "write", "tool_input": "A.groovy"}, tmp_path) == []
    outside.unlink()


def test_after_write_block_lists_errors_first_and_caps(tmp_path: Path) -> None:
    rows = [
        {
            "path": "A.groovy",
            "line": 9,
            "column": 1,
            "severity": "warning",
            "level": 2,
            "source": "s",
            "code": "W",
            "message": "w",
        },
        {
            "path": "A.groovy",
            "line": 3,
            "column": 5,
            "severity": "error",
            "level": 1,
            "source": "groovyc",
            "code": "E",
            "message": "bad",
        },
        {
            "path": "A.groovy",
            "line": 4,
            "column": 1,
            "severity": "info",
            "level": 3,
            "source": "s",
            "code": "I",
            "message": "i",
        },
    ]
    assert hooks.render_after_write([], [tmp_path / "A.groovy"], tmp_path, 12) == ""
    block = hooks.render_after_write(rows, [tmp_path / "A.groovy"], tmp_path, 2)
    lines = block.splitlines()
    assert lines[0] == "groovyscope checked A.groovy: 1 error(s), 1 warning(s), 3 finding(s)"
    assert lines[1] == "  A.groovy:3:5 error [groovyc/E] bad"
    assert lines[2].startswith("  A.groovy:9:1 warning")
    assert lines[3].startswith("  ... 1 more")
    assert lines[4].startswith("  fix the errors")


def test_changed_groovy_files_from_git_status(tmp_path: Path) -> None:
    (tmp_path / "A.groovy").write_text("class A {}")
    (tmp_path / "B.groovy").write_text("class B {}")
    (tmp_path / "build.gradle").write_text("")
    status = " M A.groovy\0?? B.groovy\0 D Gone.groovy\0A  build.gradle\0 M README.md\0"

    def runner(command: list[str], cwd: Path) -> tuple[int, str]:
        assert command[:2] == ["git", "status"] and "-z" in command and cwd == tmp_path
        return (0, status)

    assert hooks.changed_groovy_files(tmp_path, runner) == [
        tmp_path / "A.groovy",
        tmp_path / "B.groovy",
        tmp_path / "build.gradle",
    ]
    assert hooks.changed_groovy_files(tmp_path, lambda _c, _d: (128, "not a repository")) == []


def test_stop_decision_blocks_on_compile_errors_only() -> None:
    clean = [{"path": "A.groovy", "line": 1, "level": 2, "message": "warn"}]
    assert hooks.stop_decision(clean, 5) is None
    broken = [{"path": "A.groovy", "line": 7, "level": 1, "message": "unexpected token"}] * 3
    decision = hooks.stop_decision(broken, 2)
    assert decision is not None and decision["decision"] == "block"
    assert "3 compile error(s)" in decision["reason"] and "(+1 more)" in decision["reason"]
    assert json.loads(json.dumps(decision)) == decision
