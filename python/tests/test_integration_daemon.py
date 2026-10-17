"""Starts the real daemon, relays an LSP session through it, and stops it (needs a JDK and the JAR)."""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

import pytest
from click.testing import CliRunner

from groovyscope import cli, daemon
from tests.test_integration_lsp import engine_available, frame, read_message

pytestmark = pytest.mark.integration


@pytest.mark.skipif(not engine_available(), reason="needs a JDK and an engine JAR (bundled or GROOVYSCOPE_JAR)")
def test_daemon_lifecycle_through_the_cli(tmp_path: Path, short_dir: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    workspace = tmp_path / "ws"
    (workspace / "src" / "main" / "groovy").mkdir(parents=True)
    (workspace / "src" / "main" / "groovy" / "Hello.groovy").write_text("class Hello { String hi() { 'hi' } }\n")
    monkeypatch.setenv(daemon.SOCKET_DIR_ENV, str(short_dir))
    monkeypatch.setenv(daemon.CACHE_DIR_ENV, str(tmp_path / "cache"))
    monkeypatch.setenv("GROOVYSCOPE_DAEMON__IDLETIMEOUTMINUTES", "1")
    runner = CliRunner()
    try:
        started = runner.invoke(cli.main, ["--root", str(workspace), "daemon", "start"])
        assert started.exit_code == 0, started.output
        assert "daemon running on" in started.output

        status = runner.invoke(cli.main, ["--root", str(workspace), "daemon", "status"])
        assert status.exit_code == 0, status.output
        assert re.search(r"running +yes", status.output)

        env = {**os.environ, "GROOVYSCOPE_LOG": "info"}
        process = subprocess.Popen(
            [sys.executable, "-m", "groovyscope.cli", "--root", str(workspace), "lsp", "--stdio"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env,
        )
        assert process.stdin is not None and process.stdout is not None
        try:
            process.stdin.write(
                frame({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"rootUri": workspace.as_uri()}})
            )
            process.stdin.flush()
            response = read_message(process.stdout)
            assert response["result"]["serverInfo"]["name"] == "groovyscope-engine"
            process.stdin.write(frame({"jsonrpc": "2.0", "id": 2, "method": "groovyscope/status"}))
            process.stdin.flush()
            engine_status = read_message(process.stdout)
            assert engine_status["result"]["daemon"] is True
            process.stdin.write(frame({"jsonrpc": "2.0", "id": 3, "method": "shutdown"}))
            process.stdin.write(frame({"jsonrpc": "2.0", "method": "exit"}))
            process.stdin.flush()
            assert process.wait(timeout=30) == 0, (
                process.stderr.read().decode("utf-8", "replace") if process.stderr else ""
            )
        finally:
            if process.poll() is None:
                process.kill()

        doctor = runner.invoke(cli.main, ["--root", str(workspace), "doctor"])
        assert "ok    daemon" in doctor.output
    finally:
        stopped = runner.invoke(cli.main, ["--root", str(workspace), "daemon", "stop"])
        assert stopped.exit_code == 0, stopped.output
    assert "daemon stopped" in stopped.output
    assert runner.invoke(cli.main, ["--root", str(workspace), "daemon", "status"]).exit_code == 3
