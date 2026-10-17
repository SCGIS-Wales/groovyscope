from __future__ import annotations

from pathlib import Path

import pytest

from groovyscope.doctor import (
    build_report,
    config_check,
    daemon_check,
    has_failures,
    jar_check,
    jdk_check,
    kiro_check,
    python_check,
)
from groovyscope.jar import JAR_ENV, locate_jar
from groovyscope.kiro import apply_changes, plan_changes


def test_python_check() -> None:
    assert python_check((3, 12, 1)) == ("ok", "python", "3.12.1")
    assert python_check((3, 11, 9))[0] == "fail"


def test_config_check_ok_and_failure(tmp_path: Path) -> None:
    ((check, config),) = [config_check(tmp_path, None, {})]
    assert check[0] == "ok" and config is not None
    bad = tmp_path / ".groovyscope.yaml"
    bad.write_text("jdk:\n  minimum: 1\n", encoding="utf-8")
    check, config = config_check(tmp_path, None, {})
    assert check[0] == "fail" and config is None
    assert "jdk.minimum" in check[2]
    check, _ = config_check(tmp_path, tmp_path / "missing.yaml", {})
    assert check[0] == "fail"


def test_jdk_check_uses_config_home(fake_jdk: Path, clean_env: dict[str, str]) -> None:
    check, info = jdk_check({"jdk": {"home": str(fake_jdk), "minimum": 17}}, clean_env)
    assert check[0] == "ok"
    assert info is not None and info["version"] == 21


def test_jdk_check_without_config_or_jdk(clean_env: dict[str, str], monkeypatch) -> None:
    monkeypatch.setattr("groovyscope.doctor.discover_jdk", lambda *_a, **_k: None)
    check, info = jdk_check(None, clean_env)
    assert check[0] == "fail" and info is None


def test_jar_check_variants(fake_jar_dir: Path, tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr("groovyscope.doctor.locate_jar", lambda env: locate_jar(env, tmp_path / "no-bundle"))
    assert jar_check({})[0] == "fail"
    monkeypatch.setattr("groovyscope.doctor.locate_jar", lambda env: locate_jar(env, fake_jar_dir))
    assert jar_check({})[0] == "ok"
    override = tmp_path / "o.jar"
    override.write_bytes(b"x")
    assert jar_check({JAR_ENV: str(override)})[0] == "warn"


def test_kiro_check_states(tmp_path: Path) -> None:
    assert kiro_check(tmp_path)[0] == "info"
    (tmp_path / ".kiro/settings").mkdir(parents=True)
    (tmp_path / ".kiro/settings/lsp.json").write_text("{}", encoding="utf-8")
    level, _, message = kiro_check(tmp_path)
    assert level == "warn" and "mcp.json" in message
    apply_changes(tmp_path, plan_changes(tmp_path))
    assert kiro_check(tmp_path)[0] == "ok"
    home = tmp_path / "kh"
    project = tmp_path / "project"
    apply_changes(home, plan_changes(home, global_scope=True))
    level, _, message = kiro_check(project, home)
    assert level == "warn" and "run `groovyscope kiro init` in this project" in message
    apply_changes(project, plan_changes(project, lsp_only=True))
    assert kiro_check(project, home)[0] == "ok"


def test_build_report_and_failures(
    tmp_path: Path, fake_jdk: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr("groovyscope.doctor.locate_jar", lambda env: locate_jar(env, tmp_path / "no-bundle"))
    env = {**clean_env, "JAVA_HOME": str(fake_jdk)}
    report = build_report(tmp_path, None, env, home=tmp_path / "home")
    assert [name for _, name, _ in report] == [
        "groovyscope",
        "python",
        "config",
        "jdk",
        "engine jar",
        "kiro files",
        "profiles",
        "daemon",
    ]
    assert has_failures(report)  # no JAR bundled
    assert not has_failures([("ok", "a", ""), ("warn", "b", ""), ("info", "c", "")])


def test_daemon_check(tmp_path: Path, monkeypatch) -> None:
    environ = {"GROOVYSCOPE_SOCKET_DIR": str(tmp_path), "GROOVYSCOPE_CACHE_DIR": str(tmp_path)}
    level, _, message = daemon_check(tmp_path, environ)
    assert level == "info" and "not running" in message
    monkeypatch.setattr("groovyscope.daemon.probe", lambda _s, _timeout=5.0: {"engine": "1.2.3"})
    level, _, message = daemon_check(tmp_path, environ)
    assert level == "ok" and "engine 1.2.3" in message
