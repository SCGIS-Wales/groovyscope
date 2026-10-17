"""Runs `groovyscope mcp` for real: MCP over stdio to the Python server, JSON-RPC to the daemon behind it."""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest
from click.testing import CliRunner

from groovyscope import cli, daemon
from tests.test_integration_lsp import engine_available

pytestmark = pytest.mark.integration

PROTOCOL = "2025-06-18"


def send(process: subprocess.Popen[bytes], message: dict[str, Any]) -> None:
    assert process.stdin is not None
    process.stdin.write(json.dumps(message).encode("utf-8") + b"\n")
    process.stdin.flush()


def receive(process: subprocess.Popen[bytes], request_id: int) -> dict[str, Any]:
    assert process.stdout is not None
    while True:
        line = process.stdout.readline()
        if not line:
            stderr = process.stderr.read().decode("utf-8", "replace") if process.stderr else ""
            msg = f"mcp server closed stdout; stderr:\n{stderr}"
            raise AssertionError(msg)
        message = json.loads(line)
        if message.get("id") == request_id:
            assert "error" not in message, message["error"]
            return message["result"]


def call_tool(process: subprocess.Popen[bytes], request_id: int, name: str, arguments: dict[str, Any]) -> Any:
    send(
        process,
        {"jsonrpc": "2.0", "id": request_id, "method": "tools/call", "params": {"name": name, "arguments": arguments}},
    )
    result = receive(process, request_id)
    assert result.get("isError") is not True, result
    return result["structuredContent"]


@pytest.mark.skipif(not engine_available(), reason="needs a JDK and an engine JAR (bundled or GROOVYSCOPE_JAR)")
def test_mcp_tools_end_to_end(tmp_path: Path, short_dir: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    workspace = tmp_path / "ws"
    source = workspace / "src" / "main" / "groovy" / "app"
    source.mkdir(parents=True)
    (source / "Greeter.groovy").write_text(
        'package app\n\nclass Greeter {\n    String greet(String name) { "Hello, ${name}" }\n}\n', encoding="utf-8"
    )
    (source / "Main.groovy").write_text(
        "package app\n\ndef greeter = new Greeter()\nprintln greeter.greet('Ada')  \n", encoding="utf-8"
    )
    monkeypatch.setenv(daemon.SOCKET_DIR_ENV, str(short_dir))
    monkeypatch.setenv(daemon.CACHE_DIR_ENV, str(tmp_path / "cache"))
    monkeypatch.setenv("GROOVYSCOPE_DAEMON__IDLETIMEOUTMINUTES", "1")
    env = {**os.environ, "GROOVYSCOPE_LOG": "info"}
    process = subprocess.Popen(
        [sys.executable, "-m", "groovyscope.cli", "--root", str(workspace), "mcp"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
    )
    try:
        send(
            process,
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": PROTOCOL,
                    "capabilities": {},
                    "clientInfo": {"name": "groovyscope-tests", "version": "0"},
                },
            },
        )
        initialized = receive(process, 1)
        assert initialized["serverInfo"]["name"] == "groovyscope"
        send(process, {"jsonrpc": "2.0", "method": "notifications/initialized"})

        send(process, {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
        names = {tool["name"] for tool in receive(process, 2)["tools"]}
        assert {"find_symbol", "impact_of", "check", "format", "find_dynamic_code", "workspace_status"} <= names

        status = call_tool(process, 3, "workspace_status", {})
        assert status["daemon"]["running"] is True and status["engine"]["daemon"] is True

        symbols = call_tool(process, 4, "find_symbol", {"query": "greet", "kind": "method"})
        assert [s["name"] for s in symbols["items"]] == ["greet"]
        assert symbols["items"][0]["location"] == {"path": "src/main/groovy/app/Greeter.groovy", "line": 4, "col": 12}

        references = call_tool(
            process, 5, "find_references", {"path": "src/main/groovy/app/Greeter.groovy", "line": 4, "col": 12}
        )
        assert [r["path"] for r in references["items"]] == ["src/main/groovy/app/Main.groovy"]

        impact = call_tool(process, 6, "impact_of", {"symbol": "app.Greeter.greet", "change": "signature"})
        assert impact["found"] and impact["reports"][0]["counts"]["direct"] == 1

        dynamic = call_tool(process, 7, "find_dynamic_code", {})
        assert dynamic["counts"] == {"untyped-class": 1}

        formatted = call_tool(process, 8, "format", {"paths": ["src/main/groovy/app/Main.groovy"]})
        assert formatted["changed"] == 1 and formatted["files"][0]["applied"] is False

        checked = call_tool(process, 9, "check", {"include": ["compile"]})
        assert checked["summary"]["errors"] == 0

        process.stdin.close()  # type: ignore[union-attr]
        assert process.wait(timeout=30) == 0, process.stderr.read().decode("utf-8", "replace")  # type: ignore[union-attr]
    finally:
        if process.poll() is None:
            process.kill()
        stopped = CliRunner().invoke(cli.main, ["--root", str(workspace), "daemon", "stop"])
        assert stopped.exit_code == 0, stopped.output
