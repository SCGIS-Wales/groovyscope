"""The Kiro check kit: `groovyscope checks prepare` and `groovyscope checks report`."""

from __future__ import annotations

from collections.abc import Sequence
from pathlib import Path
from typing import Any

import pytest
from click.testing import CliRunner

from groovyscope import checks, cli, trust
from groovyscope.cli import checks as cli_checks
from groovyscope.cli import kiro as cli_kiro

SOURCE = "package org.codenarc.report\n\nclass JsonReportWriter {\n    String title\n}\n"


def corpus(tmp_path: Path) -> Path:
    root = tmp_path / "corpus"
    target = root / checks.CHANGED_FILE
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(SOURCE, encoding="utf-8")
    (root / ".groovyscope.yaml").write_text("workspace:\n  sourceRoots: [src/main/groovy]\n", encoding="utf-8")
    return root


def test_with_change_adds_the_method_as_the_last_member() -> None:
    changed = checks.with_change(SOURCE)
    assert changed.startswith(SOURCE.rstrip()[:-1].rstrip())
    assert changed.rstrip().endswith("}") and "catch (Exception e) {" in changed
    assert changed.count("class JsonReportWriter") == 1


def test_prepare_builds_the_workspace_with_a_change_branch(tmp_path: Path) -> None:
    commands: list[tuple[str, ...]] = []

    def runner(command: Sequence[str], cwd: Path) -> None:
        commands.append(tuple(command))
        assert cwd == tmp_path / "ws"

    done = checks.prepare(tmp_path / "ws", corpus(tmp_path), runner)
    target = tmp_path / "ws"
    assert checks.is_checks_workspace(target)
    assert (target / "Jenkinsfile").read_text(encoding="utf-8") == checks.JENKINSFILE
    assert (target / "build.gradle").read_text(encoding="utf-8") == checks.BUILD_GRADLE
    assert "catch (Exception e)" in (target / checks.CHANGED_FILE).read_text(encoding="utf-8")
    assert ".kiro/" in (target / ".git" / "info" / "exclude").read_text(encoding="utf-8")
    assert commands[0] == ("git", "init", "-q", "-b", "main")
    assert ("git", "checkout", "-q", "-b", "change") in commands
    commits = [c for c in commands if "commit" in c]
    assert len(commits) == 2 and all("user.email=checks@groovyscope.invalid" in c for c in commits)
    assert len(done) == 3 and "change" in done[2]


def test_report_lists_facts_doctor_lines_and_an_empty_result_table() -> None:
    lines = checks.report({"groovyscope": "1.0"}, [("ok", "jdk", "JDK 21"), ("info", "mcp client", "kiro-cli")])
    assert lines[0] == "## groovyscope Kiro check report"
    assert "- groovyscope: 1.0" in lines and "- info mcp client: kiro-cli" in lines
    assert lines.count("| Check | Result (pass, fail, n/a) | Notes |") == 1
    assert sum(line.startswith("| ") and line.endswith("| | |") for line in lines) == len(checks.REPORT_ROWS)


@pytest.fixture
def kit(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    calls: dict[str, Any] = {"merged": [], "trusted": []}
    monkeypatch.setattr(cli_kiro, "unpack_benchmark", lambda _environ: corpus(tmp_path))
    monkeypatch.setattr(cli_checks, "_CHECKS_RUNNER", lambda _command, _cwd: None)
    monkeypatch.setattr(cli_kiro, "run_kiro_merge", lambda obj, **_kwargs: calls["merged"].append(obj["root"]))
    monkeypatch.setattr(trust, "set_trusted", lambda root, _home, *, trusted: calls["trusted"].append((root, trusted)))
    return calls


def test_checks_prepare_never_replaces_a_directory_it_did_not_make(tmp_path: Path, kit: dict[str, Any]) -> None:
    runner = CliRunner()
    target = tmp_path / "ws"
    made = runner.invoke(cli.main, ["checks", "prepare", str(target)])
    assert made.exit_code == 0, made.output
    assert kit["merged"] == [target.resolve()] and kit["trusted"] == [(target.resolve(), True)]
    assert f"cd {target.resolve()}" in made.output and "kiro-cli chat --agent groovy" in made.output

    again = runner.invoke(cli.main, ["checks", "prepare", str(target)])
    assert again.exit_code == 1 and "pass --force" in again.output
    forced = runner.invoke(cli.main, ["checks", "prepare", "--force", str(target)])
    assert forced.exit_code == 0, forced.output

    foreign = tmp_path / "mine"
    foreign.mkdir()
    (foreign / "keep.txt").write_text("x", encoding="utf-8")
    refused = runner.invoke(cli.main, ["checks", "prepare", "--force", str(foreign)])
    assert refused.exit_code == 1 and "left alone" in refused.output
    assert (foreign / "keep.txt").exists()


def test_checks_prepare_reports_a_failed_step(tmp_path: Path, kit: dict[str, Any], monkeypatch) -> None:
    def failing(_command: Sequence[str], _cwd: Path) -> None:
        raise OSError("git not found")

    monkeypatch.setattr(cli_checks, "_CHECKS_RUNNER", failing)
    result = CliRunner().invoke(cli.main, ["checks", "prepare", str(tmp_path / "ws")])
    assert result.exit_code == 1 and "git not found" in result.output
    assert kit["merged"] == []


def test_checks_report_prints_the_block(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(cli_checks, "_HOOK_TIMER", lambda _root: 0.25)
    monkeypatch.setattr(cli_checks, "build_report", lambda *_a, **_k: [("info", "mcp client", "kiro-cli 2.29.1")])
    result = CliRunner().invoke(cli.main, ["--root", str(tmp_path), "checks", "report"])
    assert result.exit_code == 0, result.output
    assert "- hook start-up: 0.25 s" in result.output and "- info mcp client: kiro-cli 2.29.1" in result.output


def test_hook_seconds_times_a_real_stop_event(tmp_path: Path) -> None:
    assert 0 < cli.hook_seconds(tmp_path) < 60
