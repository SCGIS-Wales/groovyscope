from __future__ import annotations

import sys
from collections.abc import Sequence
from pathlib import Path

import pytest

from groovyscope import java
from groovyscope.java import (
    candidate_homes,
    default_runner,
    describe,
    discover,
    discover_jdk,
    java_executable,
    jdk_status,
    normalize_home,
    parse_major_version,
    parse_release,
    probe_version,
    release_version,
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
        if command[0] == "/usr/libexec/java_home":
            assert command[2] == "21+"
            return (0, "/Library/Java/jdk-21\n")
        assert command[1:] == ["-XshowSettings:properties", "-version"]
        return (0, "Property settings:\n    java.home = /opt/managed/jdk-21\n    java.vendor = X\n")

    homes = candidate_homes(
        "~/configured",
        {"JAVA_HOME": "/env/jdk"},
        platform="darwin",
        which=lambda _name: "/usr/local/shims/bin/java",
        runner=runner,
        minimum=21,
        scan=False,
    )
    assert [source for source, _ in homes] == ["config jdk.home", "JAVA_HOME", "/usr/libexec/java_home", "PATH"]
    assert homes[0][1] == Path("~/configured").expanduser()
    assert homes[2][1] == Path("/Library/Java/jdk-21")
    assert homes[3][1] == Path("/opt/managed/jdk-21")


def test_candidate_homes_skips_absent_sources() -> None:
    homes = candidate_homes(None, {}, platform="linux", which=lambda _name: None, runner=lambda _c: (1, ""), scan=False)
    assert homes == []


def test_candidate_homes_ignores_failed_java_home_tool() -> None:
    homes = candidate_homes(
        None, {}, platform="darwin", which=lambda _n: None, runner=lambda _c: (1, "no JVM"), scan=False
    )
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
    found = discover_jdk(str(old), {"JAVA_HOME": str(new)}, platform="linux", which=lambda _n: None, scan=False)
    assert found == {"home": str(new.resolve()), "version": 21, "source": "JAVA_HOME", "full_version": "21.0.1"}


def test_discover_jdk_reports_best_below_minimum(tmp_path: Path) -> None:
    older = make_fake_jdk(tmp_path, "1.8.0_392")
    old = make_fake_jdk(tmp_path, "11.0.2")
    found = discover_jdk(str(older), {"JAVA_HOME": str(old)}, platform="linux", which=lambda _n: None, scan=False)
    assert found is not None
    assert found["version"] == 11


def test_discover_jdk_deduplicates_and_handles_nothing(tmp_path: Path) -> None:
    jdk = make_fake_jdk(tmp_path, "17.0.1")
    calls: list[Sequence[str]] = []

    def runner(command: Sequence[str]) -> tuple[int, str]:
        calls.append(command)
        return default_runner(command)

    found = discover_jdk(
        str(jdk), {"JAVA_HOME": str(jdk)}, platform="linux", which=lambda _n: None, runner=runner, scan=False
    )
    assert found is not None and found["version"] == 17
    assert len(calls) == 1
    assert discover_jdk(None, {}, platform="linux", which=lambda _n: None, scan=False) is None


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


def corretto_bundle(root: Path, major: str, full: str) -> Path:
    """A macOS-style ``amazon-corretto-N.jdk`` bundle with a ``release`` file and a non-runnable java stub."""
    home = root / f"amazon-corretto-{major}.jdk" / "Contents" / "Home"
    (home / "bin").mkdir(parents=True)
    java_bin = home / "bin" / "java"
    java_bin.write_text("#!/bin/sh\nexit 1\n", encoding="utf-8")
    java_bin.chmod(0o755)
    (home / "release").write_text(
        f'IMPLEMENTOR="Amazon.com Inc."\nIMPLEMENTOR_VERSION="Corretto-{full}"\nJAVA_VERSION="{full}"\n'
        'JAVA_VERSION_DATE="2026-08-18"\n',
        encoding="utf-8",
    )
    return home


def test_release_file_gives_version_and_vendor_without_running_java(tmp_path: Path) -> None:
    home = corretto_bundle(tmp_path, "21", "21.0.12.1")
    assert parse_release('JAVA_VERSION="1.8.0_402"\nOS_NAME=Darwin\n') == {
        "JAVA_VERSION": "1.8.0_402",
        "OS_NAME": "Darwin",
    }
    assert release_version(home) == (21, "21.0.12.1", "Corretto-21.0.12.1")
    assert release_version(tmp_path) is None
    (tmp_path / "release").write_text('JAVA_VERSION="weird"\n', encoding="utf-8")
    assert release_version(tmp_path) is None

    def never(command: Sequence[str]) -> tuple[int, str]:
        raise AssertionError(f"no JVM should start: {command}")

    found = discover(None, {"JAVA_HOME": str(home)}, platform="linux", which=lambda _n: None, runner=never, scan=False)
    assert found["jdk"] is not None
    assert describe(found["jdk"]) == "JDK 21 (Corretto-21.0.12.1, 21.0.12.1)"
    assert describe({"home": "/x", "version": 17, "source": "PATH"}) == "JDK 17"


def test_java_home_may_point_at_the_bundle_bin_or_binary(tmp_path: Path) -> None:
    home = corretto_bundle(tmp_path, "21", "21.0.12.1")
    bundle = home.parent.parent
    assert normalize_home(bundle) == home
    assert normalize_home(home / "bin") == home
    assert normalize_home(home / "bin" / "java") == home
    assert normalize_home(tmp_path / "nothing") == tmp_path / "nothing"
    found = discover(None, {"JAVA_HOME": str(bundle)}, platform="linux", which=lambda _n: None, scan=False)
    assert found["jdk"] is not None and found["jdk"]["source"] == "JAVA_HOME"


def test_unusable_or_old_explicit_jdks_are_reported(tmp_path: Path) -> None:
    old = corretto_bundle(tmp_path / "a", "11", "11.0.25")
    new = corretto_bundle(tmp_path / "b", "21", "21.0.12.1")
    found = discover(
        str(tmp_path / "missing"),
        {"JAVA_HOME": str(old)},
        platform="linux",
        which=lambda _n: str(new / "bin" / "java"),
        scan=False,
        minimum=17,
    )
    assert found["jdk"] is not None and found["jdk"]["source"] == "PATH"
    assert any("config jdk.home" in note and "not a JDK home" in note for note in found["notes"])
    assert any("JAVA_HOME" in note and "JDK 11, below the minimum 17" in note for note in found["notes"])


def test_standard_folders_are_scanned(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    corretto_bundle(tmp_path / "jvms", "25", "25.0.4.1")
    corretto_bundle(tmp_path / "jvms", "21", "21.0.12.1")
    monkeypatch.setitem(java.STANDARD_JVM_DIRS, "linux", (str(tmp_path / "jvms"), str(tmp_path / "absent")))
    found = discover(None, {}, platform="linux", which=lambda _n: None)
    assert found["jdk"] is not None and found["jdk"]["version"] == 25
    assert found["jdk"]["source"] == "installed JDKs"
    monkeypatch.setitem(java.STANDARD_JVM_DIRS, "darwin", ("~/jvms",))
    homes = java.standard_homes("darwin", tmp_path)
    assert [h.parent.parent.name for h in homes] == ["amazon-corretto-25.jdk", "amazon-corretto-21.jdk"]


def test_path_shim_without_java_home_property_is_skipped(tmp_path: Path) -> None:
    assert java.path_java_home(lambda _n: str(tmp_path / "shim" / "java"), lambda _c: (1, "")) is None
    assert java.path_java_home(lambda _n: None, lambda _c: (0, "")) is None
    assert probe_version(tmp_path, lambda _c: (0, "")) is None
