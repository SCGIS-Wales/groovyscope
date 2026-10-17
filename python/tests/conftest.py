"""Shared fixtures: an isolated environment, a fake JDK and a fake engine JAR."""

from __future__ import annotations

import os
import shutil
import stat
import uuid
from collections.abc import Iterator
from pathlib import Path

import pytest
from click.testing import CliRunner

from groovyscope.jar import sha256_of, write_manifest

FAKE_JAVA_SCRIPT = """#!/bin/sh
if [ "$1" = "-version" ]; then
  echo 'openjdk version "{version}" 2026-01-01' >&2
  exit 0
fi
echo "fake java: $@"
"""


@pytest.fixture(autouse=True)
def isolated_home(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> Path:
    """Every test gets its own HOME and no KIRO_HOME, so the developer's ~/.kiro and runtime profiles stay unread."""
    home = tmp_path / "home"
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
        "HOME": str(tmp_path / "home"),
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
