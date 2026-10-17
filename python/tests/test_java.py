from __future__ import annotations

import sys
from collections.abc import Sequence
from pathlib import Path

import pytest

from groovyscope.java import (
    candidate_homes,
    default_runner,
    discover_jdk,
    java_executable,
    jdk_status,
    parse_major_version,
    probe_version,
)
from tests.conftest import make_fake_jdk


@pytest.mark.parametrize(
    ("output", "expected"),
    [
        ('openjdk version "21.0.12.1" 2026-08-18', 21),
        ('java version "17" 2021-09-14 LTS', 17),
        ('openjdk version "1.8.0_392"', 8),
        ('openjdk version "11.0.2" 2019-01-15', 11),
        ("Unable to locate a Java Runtime.", None),
        ("", None),
    ],
)
def test_parse_major_version(output: str, expected: int | None) -> None:
    assert parse_major_version(output) == expected


def test_java_executable() -> None:
    assert java_executable(Path("/jdk")) == Path("/jdk/bin/java")


def test_candidate_homes_priority(tmp_path: Path) -> None:
    def runner(command: Sequence[str]) -> tuple[int, str]:
        assert command[0] == "/usr/libexec/java_home"
        return (0, "/Library/Java/jdk-21\n")

    homes = candidate_homes(
        "~/configured",
        {"JAVA_HOME": "/env/jdk"},
        platform="darwin",
        which=lambda _name: "/usr/local/jdk/bin/java",
        runner=runner,
    )
    assert [source for source, _ in homes] == ["config jdk.home", "JAVA_HOME", "/usr/libexec/java_home", "PATH"]
    assert homes[0][1] == Path("~/configured").expanduser()
    assert homes[2][1] == Path("/Library/Java/jdk-21")
    assert homes[3][1] == Path("/usr/local/jdk")


def test_candidate_homes_skips_absent_sources() -> None:
    homes = candidate_homes(None, {}, platform="linux", which=lambda _name: None, runner=lambda _c: (1, ""))
    assert homes == []


def test_candidate_homes_ignores_failed_java_home_tool() -> None:
    homes = candidate_homes(None, {}, platform="darwin", which=lambda _n: None, runner=lambda _c: (1, "no JVM"))
    assert homes == []


def test_probe_version_missing_executable(tmp_path: Path) -> None:
    assert probe_version(tmp_path, lambda _c: (0, "")) is None


def test_probe_version_failed_command(fake_jdk: Path) -> None:
    assert probe_version(fake_jdk, lambda _c: (1, "boom")) is None


def test_probe_version_uses_the_real_runner(fake_jdk: Path) -> None:
    assert probe_version(fake_jdk, default_runner) == 21


def test_discover_jdk_returns_first_meeting_minimum(tmp_path: Path) -> None:
    old = make_fake_jdk(tmp_path, "11.0.2")
    new = make_fake_jdk(tmp_path, "21.0.1")
    found = discover_jdk(str(old), {"JAVA_HOME": str(new)}, platform="linux", which=lambda _n: None)
    assert found == {"home": str(new.resolve()), "version": 21, "source": "JAVA_HOME"}


def test_discover_jdk_reports_best_below_minimum(tmp_path: Path) -> None:
    older = make_fake_jdk(tmp_path, "1.8.0_392")
    old = make_fake_jdk(tmp_path, "11.0.2")
    found = discover_jdk(str(older), {"JAVA_HOME": str(old)}, platform="linux", which=lambda _n: None)
    assert found is not None
    assert found["version"] == 11


def test_discover_jdk_deduplicates_and_handles_nothing(tmp_path: Path) -> None:
    jdk = make_fake_jdk(tmp_path, "17.0.1")
    calls: list[Sequence[str]] = []

    def runner(command: Sequence[str]) -> tuple[int, str]:
        calls.append(command)
        return default_runner(command)

    found = discover_jdk(str(jdk), {"JAVA_HOME": str(jdk)}, platform="linux", which=lambda _n: None, runner=runner)
    assert found is not None and found["version"] == 17
    assert len(calls) == 1
    assert discover_jdk(None, {}, platform="linux", which=lambda _n: None) is None


def test_jdk_status_levels() -> None:
    assert jdk_status(None)[0] == "fail"
    assert jdk_status({"home": "/x", "version": 11, "source": "PATH"})[0] == "fail"
    assert jdk_status({"home": "/x", "version": 17, "source": "PATH"})[0] == "warn"
    level, message = jdk_status({"home": "/x", "version": 21, "source": "PATH"})
    assert level == "ok"
    assert "/x" in message and "PATH" in message


def test_default_runner_success_and_failure() -> None:
    code, output = default_runner([sys.executable, "-c", "print('hi')"])
    assert code == 0
    assert "hi" in output
    code, output = default_runner(["/definitely/not/a/binary"])
    assert code == -1
    assert output
