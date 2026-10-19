"""Boots the real engine through `groovyscope lsp --stdio` and completes an LSP lifecycle."""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest

from groovyscope.jar import locate_jar
from groovyscope.java import discover_jdk

pytestmark = pytest.mark.integration


def frame(message: dict[str, Any]) -> bytes:
    body = json.dumps(message).encode("utf-8")
    return f"Content-Length: {len(body)}\r\n\r\n".encode() + body


def read_message(stream: Any) -> dict[str, Any]:
    length = 0
    while True:
        line = stream.readline()
        if not line:
            msg = "engine closed the stream"
            raise AssertionError(msg)
        if line in (b"\r\n", b"\n"):
            break
        name, _, value = line.decode("ascii").partition(":")
        if name.lower() == "content-length":
            length = int(value.strip())
    loaded: dict[str, Any] = json.loads(stream.read(length))
    return loaded


def engine_available() -> bool:
    """Whether the engine tests can run; with GROOVYSCOPE_REQUIRE_ENGINE=1 (CI) a missing JAR or JDK fails the run
    instead of skipping its integration tests."""
    found = locate_jar()["path"] is not None and discover_jdk() is not None
    if not found and os.environ.get("GROOVYSCOPE_REQUIRE_ENGINE") == "1":
        pytest.fail("GROOVYSCOPE_REQUIRE_ENGINE=1, but no engine JAR or JDK was found", pytrace=False)
    return found


@pytest.mark.skipif(not engine_available(), reason="needs a JDK and an engine JAR (bundled or GROOVYSCOPE_JAR)")
def test_lifecycle_over_stdio(tmp_path: Path) -> None:
    process = subprocess.Popen(
        [sys.executable, "-m", "groovyscope.cli", "--root", str(tmp_path), "lsp", "--stdio"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env={**os.environ, "GROOVYSCOPE_LOG": "info"},
    )
    assert process.stdin is not None and process.stdout is not None
    try:
        process.stdin.write(
            frame({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"rootUri": tmp_path.as_uri()}})
        )
        process.stdin.flush()
        response = read_message(process.stdout)
        assert response["id"] == 1
        assert response["result"]["serverInfo"]["name"] == "groovyscope-engine"
        process.stdin.write(frame({"jsonrpc": "2.0", "method": "initialized", "params": {}}))
        process.stdin.write(frame({"jsonrpc": "2.0", "id": 2, "method": "shutdown"}))
        process.stdin.flush()
        assert read_message(process.stdout)["id"] == 2
        process.stdin.write(frame({"jsonrpc": "2.0", "method": "exit"}))
        process.stdin.flush()
        code = process.wait(timeout=30)
        assert code == 0, process.stderr.read().decode("utf-8", "replace") if process.stderr else ""
    finally:
        if process.poll() is None:
            process.kill()
