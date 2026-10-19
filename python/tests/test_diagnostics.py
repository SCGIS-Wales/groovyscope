"""Debug logging, `groovyscope logs` and the diagnostics bundle."""

from __future__ import annotations

import json
import logging
import zipfile
from pathlib import Path
from typing import Any

import pytest
from click.testing import CliRunner

from groovyscope import cli, diagnostics


def test_debug_flag_logs_steps_to_a_file(
    runner: CliRunner, tmp_path: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    log_file = tmp_path / "logs" / "gs.log"
    result = runner.invoke(cli.main, ["--debug", "--log-file", str(log_file), "--root", str(tmp_path), "lsp"])
    assert result.exit_code == 2  # lsp without --stdio, after logging was configured
    assert "groovyscope" in log_file.read_text(encoding="utf-8") and "DEBUG" in log_file.read_text(encoding="utf-8")
    logger = logging.getLogger("groovyscope")
    assert logger.level == logging.DEBUG
    cli.configure_logging({"GROOVYSCOPE_LOG": "info"})
    assert logger.level == logging.INFO


def test_config_logging_applies_without_flags(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("GROOVYSCOPE_LOG", raising=False)
    effective: dict[str, Any] = {"logging": {"level": "debug", "file": str(tmp_path / "x.log")}}
    cli.apply_config_logging({"log_level": None, "log_file": None}, effective)
    assert logging.getLogger("groovyscope").level == logging.DEBUG
    flags: dict[str, Any] = {"logging": {"level": "warning", "file": None}}
    cli.apply_config_logging({"log_level": "info", "log_file": tmp_path / "y.log"}, flags)
    assert flags["logging"] == {"level": "info", "file": str(tmp_path / "y.log")}
    assert diagnostics.level_of("nonsense") == logging.WARNING


def test_logs_command_shows_the_daemon_log_and_kiro_lines(
    runner: CliRunner, tmp_path: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    log_file = cli.daemon_paths(tmp_path.resolve())[1]
    log_file.parent.mkdir(parents=True)
    log_file.write_text("\n".join(f"line {i}" for i in range(10)) + "\n", encoding="utf-8")
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "logs", "-n", "3"])
    assert result.exit_code == 0 and result.output.splitlines()[-3:] == ["line 7", "line 8", "line 9"]
    kiro = tmp_path / "tmp" / "kiro-log"
    kiro.mkdir(parents=True)
    (kiro / "lsp.log").write_text("x\ngroovyscope started\nother\n", encoding="utf-8")
    (kiro / "kiro-chat.log").write_text("groovyscope secret chat\n", encoding="utf-8")
    monkeypatch.setenv("TMPDIR", str(tmp_path / "tmp"))
    result = runner.invoke(cli.main, ["logs", "--kiro"])
    assert "groovyscope started" in result.output and "secret chat" not in result.output


def test_follow_prints_appended_lines(tmp_path: Path) -> None:
    path = tmp_path / "d.log"
    path.write_text("old\n", encoding="utf-8")
    seen: list[str] = []
    rounds = iter([False, False, True])

    def sleep(_s: float) -> None:
        with path.open("a", encoding="utf-8") as handle:
            handle.write("new\n")

    diagnostics.follow(path, seen.append, sleep=sleep, stop=lambda: next(rounds))
    assert seen == ["new"]
    assert diagnostics.tail(tmp_path / "missing.log", 5) == []
    assert diagnostics.tail(path, 0) == []


def test_bundle_contents_and_redaction(
    runner: CliRunner, tmp_path: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("GROOVYSCOPE_API_TOKEN", "hunter2")
    monkeypatch.setenv("GROOVYSCOPE_DAEMON__HEAP", "2g")
    monkeypatch.setenv("TMPDIR", str(tmp_path / "tmp"))
    target = tmp_path / "out" / "bundle.zip"
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "doctor", "--bundle", str(target)])
    assert "diagnostics bundle written" in result.output
    with zipfile.ZipFile(target) as archive:
        names = set(archive.namelist())
        environment = json.loads(archive.read("environment.json"))
        doctor = archive.read("doctor.txt").decode()
    assert {"doctor.txt", "versions.json", "environment.json", "config.json", "daemon.log"} <= names
    assert "kiro-lsp.log" in names and "kiro-kiro-chat.log" not in names
    assert environment["GROOVYSCOPE_API_TOKEN"] == "<redacted>"  # noqa: S105
    assert environment["GROOVYSCOPE_DAEMON__HEAP"] == "2g"
    assert "jdk" in doctor


def test_timed_tool_wrapper_logs_and_keeps_the_signature(caplog: pytest.LogCaptureFixture) -> None:
    import inspect  # noqa: PLC0415

    def tool(path: str, limit: int = 3) -> dict[str, Any]:
        """Doc."""
        if path == "boom":
            msg = "engine failed"
            raise RuntimeError(msg)
        return {"path": path, "limit": limit}

    logger = logging.getLogger("groovyscope.test")
    logger.propagate = True
    wrapped = diagnostics.timed("tool", tool, logger)
    assert inspect.signature(wrapped) == inspect.signature(tool) and wrapped.__doc__ == "Doc."
    with caplog.at_level(logging.DEBUG, logger="groovyscope.test"):
        assert wrapped(path="a") == {"path": "a", "limit": 3}
        with pytest.raises(RuntimeError):
            wrapped(path="boom")
    assert any("answered in" in r.message for r in caplog.records)
    assert any("failed after" in r.message for r in caplog.records)


def test_log_records_stay_on_one_line_and_a_bad_log_file_is_reported(tmp_path: Path) -> None:
    log_file = tmp_path / "gs.log"
    diagnostics.configure(logging.INFO, log_file)
    logger = logging.getLogger("groovyscope.test")
    logger.warning("checked %s", "a\nb.groovy")
    try:
        raise ValueError("bad\nline")
    except ValueError:
        logger.exception("failed on %s", "x\ry")
    lines = log_file.read_text(encoding="utf-8").splitlines()
    assert len(lines) == 3
    assert lines[0].endswith("checked a\\nb.groovy") and lines[1].endswith("failed on x\\ry")
    assert "ValueError: bad\\nline" in lines[2] and "Traceback" in lines[2]
    assert diagnostics.escape_controls("tab\there\x1b") == "tab\there\\x1b"
    assert diagnostics.escape_controls("a\x85b\u2028c\u2029") == "a\\x85b\\u2028c\\u2029"
    assert len(diagnostics.escape_controls("x\u2028y").splitlines()) == 1
    blocked = tmp_path / "file"
    blocked.write_text("x", encoding="utf-8")
    diagnostics.configure(logging.WARNING, blocked / "sub" / "gs.log")
    root = logging.getLogger(diagnostics.ROOT_LOGGER)
    assert len(root.handlers) == 1
    diagnostics.configure(logging.WARNING, None)
