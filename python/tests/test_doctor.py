from __future__ import annotations

import json
from pathlib import Path

import pytest

from groovyscope import daemon
from groovyscope.doctor import (
    build_report,
    client_check,
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
    monkeypatch.setattr("groovyscope.doctor.discover", lambda *_a, **_k: {"jdk": None, "notes": []})
    check, info = jdk_check(None, clean_env)
    assert check[0] == "fail" and info is None


def test_jdk_check_reports_an_ignored_java_home(clean_env: dict[str, str], monkeypatch) -> None:
    chosen = {"home": "/jdk25", "version": 25, "source": "PATH"}
    note = "JAVA_HOME (/old) is JDK 11, below the minimum 17; ignored"
    monkeypatch.setattr("groovyscope.doctor.discover", lambda *_a, **_k: {"jdk": chosen, "notes": [note]})
    check, info = jdk_check(None, clean_env)
    assert check[0] == "warn" and note in check[2] and info == chosen


def test_jar_check_variants(fake_jar_dir: Path, tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr("groovyscope.doctor.locate_jar", lambda env: locate_jar(env, tmp_path / "no-bundle"))
    assert jar_check({})[0] == "fail"
    monkeypatch.setattr("groovyscope.doctor.locate_jar", lambda env: locate_jar(env, fake_jar_dir))
    assert jar_check({})[0] == "ok"
    override = tmp_path / "o.jar"
    override.write_bytes(b"x")
    assert jar_check({JAR_ENV: str(override)})[0] == "warn"


def test_jar_check_names_the_embedded_codenarc(fake_jar_dir: Path, monkeypatch) -> None:
    manifest = json.loads((fake_jar_dir / "_manifest.json").read_text(encoding="utf-8"))
    (fake_jar_dir / "_manifest.json").write_text(json.dumps({**manifest, "codenarc": "4.0.0"}), encoding="utf-8")
    monkeypatch.setattr("groovyscope.doctor.locate_jar", lambda env: locate_jar(env, fake_jar_dir))
    level, _, message = jar_check({})
    assert level == "ok" and "CodeNarc 4.0.0" in message


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
    project.mkdir()
    assert kiro_check(project, home)[0] == "info"  # not a Groovy project: nothing to do here
    (project / "build.gradle").write_text("", encoding="utf-8")
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
        "kiro agent",
        "kiro steering",
        "profiles",
        "daemon",
        "mcp client",
        "cache",
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
    monkeypatch.setattr(
        "groovyscope.daemon.probe",
        lambda _s, _timeout=5.0: {"engine": "1.2.3", "memory": {"usedMb": 950, "maxMb": 1000}},
    )
    level, _, message = daemon_check(tmp_path, environ)
    assert level == "warn" and "950 of 1000 MB" in message and "daemon.heap" in message
    monkeypatch.setattr("groovyscope.daemon.probe", lambda _s, _timeout=5.0: None)
    log_file = daemon.log_path(tmp_path, environ)
    log_file.parent.mkdir(parents=True, exist_ok=True)
    log_file.write_text("Terminating due to java.lang.OutOfMemoryError: Java heap space\n")
    level, _, message = daemon_check(tmp_path, environ)
    assert level == "warn" and "OutOfMemoryError" in message and "daemon.heap" in message


def test_kiro_agent_check_names_version_and_format(tmp_path: Path) -> None:
    from groovyscope.doctor import kiro_agent_check  # noqa: PLC0415

    def run(arguments: list[str]) -> tuple[int, str]:
        if arguments == ["--version"]:
            return (0, "kiro-cli 2.32.0")
        return (0, "  = unchanged  groovy.json  already universal")

    home = tmp_path / "kh"
    level, _, message = kiro_agent_check(tmp_path, home, None)
    assert level == "info" and "kiro-cli not found" in message
    agent = home / "agents" / "groovy.json"
    agent.parent.mkdir(parents=True)
    agent.write_text('{"tools": [], "permissions": {}, "kiroUpgrade": {"revision": 2}}')
    level, _, message = kiro_agent_check(tmp_path, home, run)
    assert level == "ok" and "kiro-cli 2.32.0" in message and "universal (loaded by the V2 and V3 engines)" in message
    agent.write_text('{"kiroUpgrade": {"revision": 7}}')
    assert kiro_agent_check(tmp_path, home, None)[0] == "warn"


def test_kiro_agent_check_warns_when_kiro_3_meets_a_classic_agent(tmp_path: Path) -> None:
    from groovyscope.doctor import kiro_agent_check  # noqa: PLC0415

    def kiro3(arguments: list[str]) -> tuple[int, str]:
        return (0, "kiro-cli 3.0.1") if arguments == ["--version"] else (1, "")

    home = tmp_path / "kh"
    agent = home / "agents" / "groovy.json"
    agent.parent.mkdir(parents=True)
    agent.write_text('{"tools": ["*"], "allowedTools": ["read"]}')
    level, _, message = kiro_agent_check(tmp_path, home, kiro3)
    assert level == "warn" and "V3 engine, which skips a Classic" in message and "--agent-format universal" in message


def test_kiro_agent_check_reports_hidden_servers_and_the_universal_option(tmp_path: Path) -> None:
    import json  # noqa: PLC0415

    from groovyscope.doctor import kiro_agent_check  # noqa: PLC0415

    home = tmp_path / "kh"
    (home / "agents").mkdir(parents=True)
    (home / "settings").mkdir()
    (home / "settings" / "mcp.json").write_text(
        json.dumps(
            {"mcpServers": {"gitlab": {"command": "x"}, "groovyscope": {"command": "g"}, "off": {"disabled": True}}}
        )
    )
    agent = home / "agents" / "groovy.json"
    agent.write_text(json.dumps({"tools": ["read", "@groovyscope"]}))

    def run(arguments: list[str]) -> tuple[int, str]:
        if arguments == ["--version"]:
            return (0, "kiro-cli 2.32.0")
        if "--help" in arguments:
            return (0, "Upgrade agent configs to the universal format")
        return (0, "  ✓ would upgrade  groovy.json")

    level, _, message = kiro_agent_check(tmp_path, home, run)
    assert level == "warn" and "hides these MCP servers: gitlab" in message and "--agent-format universal" in message
    agent.write_text(json.dumps({"tools": ["*"]}))
    level, _, message = kiro_agent_check(tmp_path, home, run)
    assert level == "ok" and "hides" not in message
    agent.write_text("not json")
    assert kiro_agent_check(tmp_path, home, None)[0] == "warn"


def test_client_check_reads_what_the_mcp_server_recorded(tmp_path: Path) -> None:
    environ = {"GROOVYSCOPE_SOCKET_DIR": str(tmp_path), "GROOVYSCOPE_CACHE_DIR": str(tmp_path)}
    assert "none recorded yet" in client_check(tmp_path, environ)[2]
    target = daemon.client_path(tmp_path, environ)
    target.parent.mkdir(parents=True)
    target.write_text('{"name":"kiro-cli","version":"2.29.1","protocol":"2025-06-18","elicitation":false}')
    level, name, message = client_check(tmp_path, environ)
    assert (level, name) == ("info", "mcp client")
    assert "kiro-cli 2.29.1" in message and "can be asked: no" in message
