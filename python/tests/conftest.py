"""Shared fixtures: an isolated environment, a fake JDK and a fake engine JAR."""

from __future__ import annotations

import os
import shutil
import stat
import uuid
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest
from click.testing import CliRunner

from groovyscope import mcp_server
from groovyscope.jar import sha256_of, write_manifest

MCP_ROOT = Path("/ws")

FAKE_JAVA_SCRIPT = """#!/bin/sh
if [ "$1" = "-version" ]; then
  echo 'openjdk version "{version}" 2026-01-01' >&2
  exit 0
fi
echo "fake java: $@"
"""


@pytest.fixture(autouse=True)
def no_real_kiro(monkeypatch: pytest.MonkeyPatch) -> None:
    """Tests never run the developer's kiro-cli; tests that need one inject a fake."""
    from groovyscope import cli  # noqa: PLC0415 - fixture-local import keeps conftest light

    monkeypatch.setattr(cli, "_KIRO", lambda: None)


@pytest.fixture(autouse=True)
def isolated_home(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> Path:
    """Every test gets its own HOME and no KIRO_HOME, so the developer's ~/.kiro and runtime profiles stay unread."""
    home = tmp_path.parent / f"{tmp_path.name}-home"  # beside, not inside, the folder tests use as a workspace
    home.mkdir(exist_ok=True)
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.delenv("KIRO_HOME", raising=False)
    return home


@pytest.fixture
def clean_env(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> dict[str, str]:
    """Environment without any GROOVYSCOPE_* or JAVA_HOME influence; PATH points at an empty dir.

    The cache directory and HOME are redirected too, so a JAR, JDK or runtime profile on the developer's machine
    stays invisible.
    """
    for name in list(os.environ):
        if name.startswith("GROOVYSCOPE_") or name == "JAVA_HOME":
            monkeypatch.delenv(name, raising=False)
    cache = tmp_path / "cache"
    env = {
        "PATH": "/nonexistent-bin",
        "HOME": str(tmp_path.parent / f"{tmp_path.name}-home"),
        "GROOVYSCOPE_CACHE_DIR": str(cache),
    }
    monkeypatch.setenv("PATH", env["PATH"])
    monkeypatch.setenv("HOME", env["HOME"])
    monkeypatch.setenv("GROOVYSCOPE_CACHE_DIR", str(cache))
    return env


def make_fake_jdk(root: Path, version: str = "21.0.1") -> Path:
    home = root / f"jdk-{version}"
    bin_dir = home / "bin"
    bin_dir.mkdir(parents=True)
    java = bin_dir / "java"
    java.write_text(FAKE_JAVA_SCRIPT.format(version=version), encoding="utf-8")
    java.chmod(java.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    return home


@pytest.fixture
def fake_jdk(tmp_path: Path) -> Path:
    return make_fake_jdk(tmp_path)


@pytest.fixture
def fake_jar_dir(tmp_path: Path) -> Path:
    """A bundle directory with a dummy JAR and a matching manifest."""
    jar_dir = tmp_path / "_jar"
    jar_dir.mkdir()
    jar = jar_dir / "groovyscope-engine-0.1.0-all.jar"
    jar.write_bytes(b"PK\x03\x04 not really a jar")
    write_manifest(jar_dir, version="0.1.0", jar_name=jar.name, sha256=sha256_of(jar))
    return jar_dir


@pytest.fixture
def runner() -> CliRunner:
    return CliRunner()


@pytest.fixture
def short_dir() -> Iterator[Path]:
    """A short path for Unix sockets; pytest's tmp_path on macOS exceeds the AF_UNIX length limit."""
    path = Path("/tmp") / f"gst-{uuid.uuid4().hex[:8]}"  # noqa: S108 - sockets need a short path
    path.mkdir()
    try:
        yield path
    finally:
        shutil.rmtree(path, ignore_errors=True)


@pytest.fixture
def engine() -> dict[str, Any]:
    """A fake engine: the calls the MCP tools made, and the responses to give them."""
    state: dict[str, Any] = {"calls": [], "responses": {}}
    return state


@pytest.fixture
def local_tools(engine: dict[str, Any], tmp_path: Path) -> dict[str, Any]:
    """Tools whose workspace root is tmp_path, for the tools that write files."""

    def call(method: str, params: dict[str, Any]) -> Any:
        engine["calls"].append((method, params))
        return engine["responses"].get(method, {})

    return mcp_server.build_tools(tmp_path, call, {"maxResults": 3, "maxBytes": 16000}, {})


@pytest.fixture
def tools(engine: dict[str, Any], tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    def call(method: str, params: dict[str, Any]) -> Any:
        engine["calls"].append((method, params))
        return engine["responses"].get(method, {})

    monkeypatch.setattr(
        mcp_server.daemon, "info", lambda _root, _environ: {"socket": "/s", "running": True, "status": {}, "log": "/l"}
    )
    return mcp_server.build_tools(MCP_ROOT, call, {"maxResults": 3, "maxBytes": 16000}, {})
