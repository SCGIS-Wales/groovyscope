"""The check and format commands against a fake engine RPC."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from click.testing import CliRunner

from groovyscope import cli


@pytest.fixture
def engine(
    tmp_path: Path, fake_jdk: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> dict[str, Any]:
    jar = tmp_path / "engine.jar"
    jar.write_bytes(b"x")
    monkeypatch.setenv("JAVA_HOME", str(fake_jdk))
    monkeypatch.setenv("GROOVYSCOPE_JAR", str(jar))
    monkeypatch.setenv("GROOVYSCOPE_SOCKET_DIR", str(tmp_path / "run"))
    monkeypatch.setenv("GROOVYSCOPE_CACHE_DIR", str(tmp_path / "cache"))
    monkeypatch.setenv(cli.CONFIG_ENV, "")
    calls: dict[str, Any] = {"requests": []}
    monkeypatch.setattr(cli.daemon_lifecycle, "ensure_running", lambda *_a, **_k: {"engine": "x"})

    def fake_rpc(socket_file: Path, method: str, params: dict[str, Any], timeout: float) -> Any:
        calls["requests"].append((method, params))
        return calls["response"]

    monkeypatch.setattr(cli, "_RPC", fake_rpc)
    return calls


def diagnostic(line: int, severity: int, source: str, code: str, message: str) -> dict[str, Any]:
    return {
        "range": {"start": {"line": line, "character": 4}, "end": {"line": line, "character": 10}},
        "severity": severity,
        "source": source,
        "code": code,
        "message": message,
    }


def test_check_prints_findings_and_fails_on_priority(runner: CliRunner, tmp_path: Path, engine: dict[str, Any]) -> None:
    uri = (tmp_path / "src" / "A.groovy").as_uri()
    engine["response"] = {
        "diagnostics": {
            uri: [
                diagnostic(2, 2, "codenarc", "UnnecessarySemicolon", "Semicolons are unnecessary"),
                diagnostic(0, 3, "codenarc", "UnusedImport", "unused"),
            ]
        },
        "summary": {"errors": 0, "warnings": 1, "information": 1, "files": 1},
    }
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "check", "src/A.groovy", "--include", "lint"])
    assert result.exit_code == 1, result.output
    assert "src/A.groovy:1:5: info [codenarc/UnusedImport] unused" in result.output
    assert "src/A.groovy:3:5: warning [codenarc/UnnecessarySemicolon]" in result.output
    assert "0 error(s), 1 warning(s), 1 info in 1 file(s)" in result.output
    method, params = engine["requests"][0]
    assert method == "groovyscope/check"
    assert params["uris"] == [uri]
    assert params["include"] == ["lint"]
    assert params["root"] == str(tmp_path)
    assert "profile" not in params


def test_check_passes_with_only_low_priority_findings_and_json(
    runner: CliRunner, tmp_path: Path, engine: dict[str, Any]
) -> None:
    uri = (tmp_path / "B.groovy").as_uri()
    engine["response"] = {
        "diagnostics": {uri: [diagnostic(0, 3, "codenarc", "Minor", "m")]},
        "summary": {"errors": 0, "warnings": 0, "information": 1, "files": 1},
    }
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "check", "--profile", "jenkins", "--json"])
    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert payload["findings"][0]["code"] == "Minor"
    assert payload["summary"]["files"] == 1
    assert engine["requests"][0][1]["profile"] == "jenkins"
    assert engine["requests"][0][1]["uris"] == []


def test_check_reports_rpc_failures(
    runner: CliRunner, tmp_path: Path, engine: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    def broken(*_a: object, **_k: object) -> Any:
        msg = "socket gone"
        raise OSError(msg)

    monkeypatch.setattr(cli, "_RPC", broken)
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "check"])
    assert result.exit_code == 1
    assert "groovyscope/check failed: socket gone" in result.output


def test_format_reports_then_applies(runner: CliRunner, tmp_path: Path, engine: dict[str, Any]) -> None:
    target = tmp_path / "src" / "Messy.groovy"
    target.parent.mkdir()
    target.write_text("class A {   \n    int x = 1;\n}\n", encoding="utf-8")
    formatted = "class A {\n    int x = 1\n}\n"
    engine["response"] = {
        "edits": {
            target.as_uri(): [
                {
                    "range": {"start": {"line": 0, "character": 0}, "end": {"line": 3, "character": 0}},
                    "newText": formatted,
                }
            ]
        },
        "changed": 1,
        "checked": 1,
    }
    dry = runner.invoke(cli.main, ["--root", str(tmp_path), "format", "src/Messy.groovy"])
    assert dry.exit_code == 1, dry.output
    assert "src/Messy.groovy: 2 line(s) changed" in dry.output
    assert "1 of 1 file(s) need formatting" in dry.output
    assert target.read_text(encoding="utf-8").startswith("class A {   ")

    applied = runner.invoke(cli.main, ["--root", str(tmp_path), "format", "--apply", "--json"])
    assert applied.exit_code == 0, applied.output
    payload = json.loads(applied.output)
    assert payload["files"][0]["applied"] is True
    assert target.read_text(encoding="utf-8") == formatted

    engine["response"] = {"edits": {}, "changed": 0, "checked": 3}
    clean = runner.invoke(cli.main, ["--root", str(tmp_path), "format"])
    assert clean.exit_code == 0
    assert "0 of 3 file(s) need formatting" in clean.output


def test_daemon_socket_failure_exits_1(
    runner: CliRunner, tmp_path: Path, engine: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    def failing(*_a: object, **_k: object) -> dict[str, Any]:
        msg = "cannot start"
        raise RuntimeError(msg)

    monkeypatch.setattr(cli.daemon_lifecycle, "ensure_running", failing)
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "check"])
    assert result.exit_code == 1
    assert "cannot start" in result.output


def test_engine_view_resolves_bundled_rulesets(tmp_path: Path) -> None:
    effective = {
        "lint": {"ruleset": None, "jenkinsRuleset": "~/my/jenkins.groovy", "failOnPriority": 2, "publish": True}
    }
    view = cli.engine_view({"root": tmp_path}, effective)
    assert view["lint"]["ruleset"].endswith("codenarc/groovyscope.groovy")
    assert Path(view["lint"]["ruleset"]).is_file()
    assert view["lint"]["jenkinsRuleset"] == str(Path("~/my/jenkins.groovy").expanduser())
    assert view["root"] == str(tmp_path)
