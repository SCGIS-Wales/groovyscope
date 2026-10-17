from __future__ import annotations

import json
import os
from collections.abc import Sequence
from pathlib import Path

import pytest
from click.testing import CliRunner

from groovyscope import __version__, cli
from groovyscope.jar import locate_jar
from groovyscope.launch import SAFETY_FLAGS


def test_version_without_bundle(runner: CliRunner, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(cli, "read_manifest", lambda: None)
    result = runner.invoke(cli.main, ["version"])
    assert result.exit_code == 0
    assert result.output == f"groovyscope {__version__}\nengine not bundled\n"


def test_version_with_bundle(runner: CliRunner, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(cli, "read_manifest", lambda: {"version": "9.9.9"})
    assert "engine 9.9.9" in runner.invoke(cli.main, ["version"]).output


def test_version_option(runner: CliRunner) -> None:
    result = runner.invoke(cli.main, ["--version"])
    assert result.exit_code == 0
    assert __version__ in result.output


def test_doctor_reports_and_fails_without_jar(
    runner: CliRunner, tmp_path: Path, fake_jdk: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("JAVA_HOME", str(fake_jdk))
    monkeypatch.setattr("groovyscope.doctor.locate_jar", lambda env: locate_jar(env, tmp_path / "no-bundle"))
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "doctor"])
    assert result.exit_code == 1
    assert "jdk" in result.output and "JDK 21" in result.output
    assert "FAIL  engine jar" in result.output
    assert "kiro files" in result.output


def test_doctor_passes_with_jar_override(
    runner: CliRunner, tmp_path: Path, fake_jdk: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    jar = tmp_path / "engine.jar"
    jar.write_bytes(b"x")
    monkeypatch.setenv("JAVA_HOME", str(fake_jdk))
    monkeypatch.setenv("GROOVYSCOPE_JAR", str(jar))
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "doctor"])
    assert result.exit_code == 0, result.output
    assert "warn  engine jar" in result.output


def test_config_validate(runner: CliRunner, tmp_path: Path, clean_env: dict[str, str]) -> None:
    ok = runner.invoke(cli.main, ["--root", str(tmp_path), "config", "validate"])
    assert ok.exit_code == 0 and ok.output == "configuration OK\n"
    shown = runner.invoke(cli.main, ["--root", str(tmp_path), "config", "validate", "--show"])
    assert shown.exit_code == 0 and shown.output.startswith("groovy:")
    (tmp_path / ".groovyscope.yaml").write_text("format:\n  engine: prettier\n", encoding="utf-8")
    bad = runner.invoke(cli.main, ["--root", str(tmp_path), "config", "validate"])
    assert bad.exit_code == 1
    assert "format.engine" in bad.output


def test_config_path_from_env(runner: CliRunner, tmp_path: Path, clean_env: dict[str, str], monkeypatch) -> None:
    custom = tmp_path / "c.yaml"
    custom.write_text("output:\n  maxResults: 7\n", encoding="utf-8")
    monkeypatch.setenv("GROOVYSCOPE_CONFIG", str(custom))
    shown = runner.invoke(cli.main, ["--root", str(tmp_path), "config", "validate", "--show"])
    assert "maxResults: 7" in shown.output


def test_kiro_init_dry_run_then_write(runner: CliRunner, tmp_path: Path) -> None:
    dry = runner.invoke(cli.main, ["--root", str(tmp_path), "kiro", "init", "--dry-run"])
    assert dry.exit_code == 0
    assert "dry run: nothing written" in dry.output
    assert not (tmp_path / ".kiro").exists()
    real = runner.invoke(cli.main, ["--root", str(tmp_path), "kiro", "init"])
    assert real.exit_code == 0, real.output
    assert "4 file(s) written" in real.output
    assert "+++ b/.kiro/settings/lsp.json" in real.output
    assert "/code status" in real.output
    again = runner.invoke(cli.main, ["--root", str(tmp_path), "kiro", "init"])
    assert "nothing to do" in again.output


def test_kiro_repair_restores_entry(runner: CliRunner, tmp_path: Path) -> None:
    runner.invoke(cli.main, ["--root", str(tmp_path), "kiro", "init"])
    lsp = tmp_path / ".kiro/settings/lsp.json"
    lsp.write_text(json.dumps({"languages": {"rust": {"command": "rust-analyzer"}}}), encoding="utf-8")
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "kiro", "repair"])
    assert result.exit_code == 0
    merged = json.loads(lsp.read_text(encoding="utf-8"))
    assert set(merged["languages"]) == {"rust", "groovy"}
    assert lsp.with_name("lsp.json.bak").exists()


def test_kiro_init_force_and_no_backup(runner: CliRunner, tmp_path: Path) -> None:
    lsp = tmp_path / ".kiro/settings/lsp.json"
    lsp.parent.mkdir(parents=True)
    lsp.write_text("not json", encoding="utf-8")
    failed = runner.invoke(cli.main, ["--root", str(tmp_path), "kiro", "init"])
    assert failed.exit_code == 1 and "not valid JSON" in failed.output
    forced = runner.invoke(cli.main, ["--root", str(tmp_path), "kiro", "init", "--force", "--no-backup"])
    assert forced.exit_code == 0
    assert "groovyscope" in lsp.read_text(encoding="utf-8")
    assert not lsp.with_name("lsp.json.bak").exists()


def test_lsp_requires_stdio(runner: CliRunner) -> None:
    result = runner.invoke(cli.main, ["lsp"])
    assert result.exit_code == 2
    assert "--stdio" in result.output


def test_lsp_execs_the_engine(
    runner: CliRunner, tmp_path: Path, fake_jdk: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    jar = tmp_path / "engine.jar"
    jar.write_bytes(b"x")
    monkeypatch.setenv("JAVA_HOME", str(fake_jdk))
    monkeypatch.setenv("GROOVYSCOPE_JAR", str(jar))
    monkeypatch.setenv("GROOVYSCOPE_DAEMON__HEAP", "2g")
    monkeypatch.setenv(cli.CONFIG_ENV, "")
    captured: list[tuple[str, Sequence[str]]] = []
    monkeypatch.setattr(cli, "_EXEC", lambda path, argv: captured.append((path, argv)))
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "lsp", "--stdio"])
    assert result.exit_code == 0, result.output
    handed_over = json.loads(os.environ[cli.CONFIG_ENV])
    assert handed_over["daemon"]["heap"] == "2g"
    assert handed_over["root"] == str(tmp_path.resolve())
    assert handed_over["workspace"]["trust"] is False
    path, argv = captured[0]
    assert path.endswith("/bin/java")
    assert argv[0] == path
    assert "-Xmx2g" in argv
    assert all(flag in argv for flag in SAFETY_FLAGS)
    assert argv[-4:] == ["-jar", str(jar), "lsp", "--stdio"]


def test_lsp_fails_cleanly_without_jdk_or_jar(
    runner: CliRunner, tmp_path: Path, fake_jdk: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(cli, "_EXEC", lambda _path, _argv: pytest.fail("must not exec"))
    with pytest.MonkeyPatch.context() as inner:
        inner.setattr(cli, "discover_jdk", lambda *_a, **_k: None)
        no_jdk = runner.invoke(cli.main, ["--root", str(tmp_path), "lsp", "--stdio"])
    assert no_jdk.exit_code == 1 and "no suitable JDK" in no_jdk.output
    monkeypatch.setenv("JAVA_HOME", str(fake_jdk))
    monkeypatch.setattr(cli, "locate_jar", lambda env: locate_jar(env, tmp_path / "no-bundle"))
    no_jar = runner.invoke(cli.main, ["--root", str(tmp_path), "lsp", "--stdio"])
    assert no_jar.exit_code == 1 and "no engine JAR" in no_jar.output
    (tmp_path / ".groovyscope.yaml").write_text("daemon: {heap: huge}\n", encoding="utf-8")
    bad_config = runner.invoke(cli.main, ["--root", str(tmp_path), "lsp", "--stdio"])
    assert bad_config.exit_code == 1 and "daemon.heap" in bad_config.output


@pytest.mark.parametrize("args", [["mcp"], ["daemon", "start"], ["daemon", "stop"], ["daemon", "status"]])
def test_placeholders_exit_3(runner: CliRunner, args: list[str]) -> None:
    result = runner.invoke(cli.main, args)
    assert result.exit_code == cli.EXIT_NOT_IMPLEMENTED
    assert "not implemented yet" in result.output


def test_configure_logging_levels() -> None:
    cli.configure_logging({"GROOVYSCOPE_LOG": "debug"})
    cli.configure_logging({"GROOVYSCOPE_LOG": "nonsense"})


def test_exec_process_replaces_the_process(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[tuple[str, list[str]]] = []
    monkeypatch.setattr(cli.os, "execv", lambda path, argv: calls.append((path, argv)))
    cli.exec_process("/bin/true", ("/bin/true", "x"))
    assert calls == [("/bin/true", ["/bin/true", "x"])]
