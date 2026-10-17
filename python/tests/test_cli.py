from __future__ import annotations

import json
import os
from collections.abc import Sequence
from pathlib import Path
from typing import Any

import pytest
from click.testing import CliRunner

from groovyscope import __version__, cli
from groovyscope.jar import locate_jar
from groovyscope.launch import SAFETY_FLAGS


def test_version_without_bundle(runner: CliRunner, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(cli, "read_manifest", lambda: None)
    result = runner.invoke(cli.main, ["version"])
    assert result.exit_code == 0
    assert result.output == f"groovyscope {__version__}\nengine not bundled\n"


def test_version_with_bundle(runner: CliRunner, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(cli, "read_manifest", lambda: {"version": "9.9.9"})
    assert "engine 9.9.9" in runner.invoke(cli.main, ["version"]).output


def test_version_option(runner: CliRunner) -> None:
    result = runner.invoke(cli.main, ["--version"])
    assert result.exit_code == 0
    assert __version__ in result.output


def test_doctor_reports_and_fails_without_jar(
    runner: CliRunner, tmp_path: Path, fake_jdk: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("JAVA_HOME", str(fake_jdk))
    monkeypatch.setattr("groovyscope.doctor.locate_jar", lambda env: locate_jar(env, tmp_path / "no-bundle"))
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "doctor"])
    assert result.exit_code == 1
    assert "jdk" in result.output and "JDK 21" in result.output
    assert "FAIL  engine jar" in result.output
    assert "kiro files" in result.output


def test_doctor_passes_with_jar_override(
    runner: CliRunner, tmp_path: Path, fake_jdk: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    jar = tmp_path / "engine.jar"
    jar.write_bytes(b"x")
    monkeypatch.setenv("JAVA_HOME", str(fake_jdk))
    monkeypatch.setenv("GROOVYSCOPE_JAR", str(jar))
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "doctor"])
    assert result.exit_code == 0, result.output
    assert "warn  engine jar" in result.output


def test_config_validate(runner: CliRunner, tmp_path: Path, clean_env: dict[str, str]) -> None:
    ok = runner.invoke(cli.main, ["--root", str(tmp_path), "config", "validate"])
    assert ok.exit_code == 0 and ok.output == "configuration OK\n"
    shown = runner.invoke(cli.main, ["--root", str(tmp_path), "config", "validate", "--show"])
    assert shown.exit_code == 0 and shown.output.startswith("groovy:")
    (tmp_path / ".groovyscope.yaml").write_text("format:\n  engine: prettier\n", encoding="utf-8")
    bad = runner.invoke(cli.main, ["--root", str(tmp_path), "config", "validate"])
    assert bad.exit_code == 1
    assert "format.engine" in bad.output


def test_config_path_from_env(runner: CliRunner, tmp_path: Path, clean_env: dict[str, str], monkeypatch) -> None:
    custom = tmp_path / "c.yaml"
    custom.write_text("output:\n  maxResults: 7\n", encoding="utf-8")
    monkeypatch.setenv("GROOVYSCOPE_CONFIG", str(custom))
    shown = runner.invoke(cli.main, ["--root", str(tmp_path), "config", "validate", "--show"])
    assert "maxResults: 7" in shown.output


def test_kiro_init_dry_run_then_write(runner: CliRunner, tmp_path: Path) -> None:
    dry = runner.invoke(cli.main, ["--root", str(tmp_path), "kiro", "init", "--dry-run"])
    assert dry.exit_code == 0
    assert "dry run: nothing written" in dry.output
    assert not (tmp_path / ".kiro").exists()
    real = runner.invoke(cli.main, ["--root", str(tmp_path), "kiro", "init"])
    assert real.exit_code == 0, real.output
    assert "4 file(s) written" in real.output
    assert "+++ b/.kiro/settings/lsp.json" in real.output
    assert "/code status" in real.output
    again = runner.invoke(cli.main, ["--root", str(tmp_path), "kiro", "init"])
    assert "nothing to do" in again.output


def test_kiro_repair_restores_entry(runner: CliRunner, tmp_path: Path) -> None:
    runner.invoke(cli.main, ["--root", str(tmp_path), "kiro", "init"])
    lsp = tmp_path / ".kiro/settings/lsp.json"
    lsp.write_text(json.dumps({"languages": {"rust": {"command": "rust-analyzer"}}}), encoding="utf-8")
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "kiro", "repair"])
    assert result.exit_code == 0
    merged = json.loads(lsp.read_text(encoding="utf-8"))
    assert set(merged["languages"]) == {"rust", "groovy"}
    assert lsp.with_name("lsp.json.bak").exists()


def test_kiro_init_force_and_no_backup(runner: CliRunner, tmp_path: Path) -> None:
    lsp = tmp_path / ".kiro/settings/lsp.json"
    lsp.parent.mkdir(parents=True)
    lsp.write_text("not json", encoding="utf-8")
    failed = runner.invoke(cli.main, ["--root", str(tmp_path), "kiro", "init"])
    assert failed.exit_code == 1 and "not valid JSON" in failed.output
    forced = runner.invoke(cli.main, ["--root", str(tmp_path), "kiro", "init", "--force", "--no-backup"])
    assert forced.exit_code == 0
    assert "groovyscope" in lsp.read_text(encoding="utf-8")
    assert not lsp.with_name("lsp.json.bak").exists()


def test_lsp_requires_stdio(runner: CliRunner) -> None:
    result = runner.invoke(cli.main, ["lsp"])
    assert result.exit_code == 2
    assert "--stdio" in result.output


def test_lsp_execs_the_engine(
    runner: CliRunner, tmp_path: Path, fake_jdk: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    jar = tmp_path / "engine.jar"
    jar.write_bytes(b"x")
    monkeypatch.setenv("JAVA_HOME", str(fake_jdk))
    monkeypatch.setenv("GROOVYSCOPE_JAR", str(jar))
    monkeypatch.setenv("GROOVYSCOPE_DAEMON__HEAP", "2g")
    monkeypatch.setenv("GROOVYSCOPE_DAEMON__ENABLED", "false")
    monkeypatch.setenv("GROOVYSCOPE_DAEMON__CDS", "false")
    monkeypatch.setenv(cli.CONFIG_ENV, "")
    captured: list[tuple[str, Sequence[str]]] = []
    monkeypatch.setattr(cli, "_EXEC", lambda path, argv: captured.append((path, argv)))
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "lsp", "--stdio"])
    assert result.exit_code == 0, result.output
    handed_over = json.loads(os.environ[cli.CONFIG_ENV])
    assert handed_over["daemon"]["heap"] == "2g"
    assert handed_over["root"] == str(tmp_path.resolve())
    assert handed_over["workspace"]["trust"] is False
    path, argv = captured[0]
    assert path.endswith("/bin/java")
    assert argv[0] == path
    assert "-Xmx2g" in argv
    assert all(flag in argv for flag in SAFETY_FLAGS)
    assert argv[-6:-2] == ["-jar", str(jar), "lsp", "--stdio"]
    assert argv[-2] == "--cache-dir"
    assert "-Xshare:auto" in argv


def test_lsp_fails_cleanly_without_jdk_or_jar(
    runner: CliRunner, tmp_path: Path, fake_jdk: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(cli, "_EXEC", lambda _path, _argv: pytest.fail("must not exec"))
    with pytest.MonkeyPatch.context() as inner:
        inner.setattr(cli, "discover", lambda *_a, **_k: {"jdk": None, "notes": ["JAVA_HOME (/x) is not a JDK home"]})
        no_jdk = runner.invoke(cli.main, ["--root", str(tmp_path), "lsp", "--stdio"])
    assert no_jdk.exit_code == 1 and "no suitable JDK" in no_jdk.output
    assert "JAVA_HOME (/x) is not a JDK home" in no_jdk.output
    monkeypatch.setenv("JAVA_HOME", str(fake_jdk))
    monkeypatch.setattr(cli, "locate_jar", lambda env: locate_jar(env, tmp_path / "no-bundle"))
    no_jar = runner.invoke(cli.main, ["--root", str(tmp_path), "lsp", "--stdio"])
    assert no_jar.exit_code == 1 and "no engine JAR" in no_jar.output
    (tmp_path / ".groovyscope.yaml").write_text("daemon: {heap: huge}\n", encoding="utf-8")
    bad_config = runner.invoke(cli.main, ["--root", str(tmp_path), "lsp", "--stdio"])
    assert bad_config.exit_code == 1 and "daemon.heap" in bad_config.output


def test_mcp_command_serves_over_the_daemon(
    runner: CliRunner, tmp_path: Path, fake_jdk: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    jar = tmp_path / "engine.jar"
    jar.write_bytes(b"x")
    monkeypatch.setenv("JAVA_HOME", str(fake_jdk))
    monkeypatch.setenv("GROOVYSCOPE_JAR", str(jar))
    monkeypatch.setenv("GROOVYSCOPE_SOCKET_DIR", str(tmp_path / "run"))
    monkeypatch.setenv("GROOVYSCOPE_CACHE_DIR", str(tmp_path / "cache"))
    monkeypatch.setenv(cli.CONFIG_ENV, "")
    monkeypatch.setattr(cli.daemon_lifecycle, "ensure_running", lambda *_a, **_k: {"engine": "x"})
    served: list[tuple[Path, Path, dict[str, Any], dict[str, str], Any]] = []
    monkeypatch.setattr(
        cli,
        "_MCP_SERVE",
        lambda root, socket_file, output, environ, reconnect: (
            served.append((root, socket_file, output, environ, reconnect)) or 0
        ),
    )
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "mcp"])
    assert result.exit_code == 0, result.output
    assert served[0][0] == tmp_path.resolve()
    assert served[0][1].suffix == ".sock"
    assert served[0][2]["maxResults"] == 50
    assert served[0][4]().suffix == ".sock"

    def failing(*_a: object, **_k: object) -> dict[str, object]:
        msg = "no JVM"
        raise RuntimeError(msg)

    monkeypatch.setattr(cli.daemon_lifecycle, "ensure_running", failing)
    with pytest.raises(ConnectionError, match="could not be restarted"):
        served[0][4]()


def test_configure_logging_levels() -> None:
    cli.configure_logging({"GROOVYSCOPE_LOG": "debug"})
    cli.configure_logging({"GROOVYSCOPE_LOG": "nonsense"})


def test_exec_process_replaces_the_process(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[tuple[str, list[str]]] = []
    monkeypatch.setattr(cli.os, "execv", lambda path, argv: calls.append((path, argv)))
    cli.exec_process("/bin/true", ("/bin/true", "x"))
    assert calls == [("/bin/true", ["/bin/true", "x"])]


def test_lsp_relays_through_the_daemon(
    runner: CliRunner, tmp_path: Path, fake_jdk: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    jar = tmp_path / "engine.jar"
    jar.write_bytes(b"x")
    monkeypatch.setenv("JAVA_HOME", str(fake_jdk))
    monkeypatch.setenv("GROOVYSCOPE_JAR", str(jar))
    monkeypatch.setenv("GROOVYSCOPE_SOCKET_DIR", str(tmp_path / "run"))
    monkeypatch.setenv("GROOVYSCOPE_CACHE_DIR", str(tmp_path / "cache"))
    monkeypatch.setenv(cli.CONFIG_ENV, "")
    ensured: list[list[str]] = []
    relayed: list[Path] = []
    monkeypatch.setattr(
        cli.daemon_lifecycle, "ensure_running", lambda cmd, _s, _l, _e, **_k: ensured.append(cmd) or {"engine": "x"}
    )
    monkeypatch.setattr(cli, "_RELAY", lambda socket_file: relayed.append(socket_file) or 0)
    monkeypatch.setattr(cli, "_EXEC", lambda _p, _a: pytest.fail("must not exec when the daemon works"))
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "lsp", "--stdio"])
    assert result.exit_code == 0, result.output
    assert relayed == [cli.daemon_lifecycle.socket_path(tmp_path, dict(os.environ))]
    assert ensured[0][-7:-3] == ["daemon", "--socket", str(relayed[0]), "--idle-seconds"]
    assert ensured[0][-2] == "--cache-dir"
    assert "-XX:+AutoCreateSharedArchive" in ensured[0]


def test_lsp_falls_back_to_direct_exec_when_the_daemon_fails(
    runner: CliRunner, tmp_path: Path, fake_jdk: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    jar = tmp_path / "engine.jar"
    jar.write_bytes(b"x")
    monkeypatch.setenv("JAVA_HOME", str(fake_jdk))
    monkeypatch.setenv("GROOVYSCOPE_JAR", str(jar))
    monkeypatch.setenv("GROOVYSCOPE_SOCKET_DIR", str(tmp_path / "run"))
    monkeypatch.setenv("GROOVYSCOPE_CACHE_DIR", str(tmp_path / "cache"))
    monkeypatch.setenv(cli.CONFIG_ENV, "")

    def broken(*_a: object, **_k: object) -> dict[str, object]:
        msg = "no JVM today"
        raise RuntimeError(msg)

    monkeypatch.setattr(cli.daemon_lifecycle, "ensure_running", broken)
    captured: list[Sequence[str]] = []
    monkeypatch.setattr(cli, "_EXEC", lambda _p, argv: captured.append(argv))
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "lsp", "--stdio"])
    assert result.exit_code == 0, result.output
    assert "daemon unavailable (no JVM today)" in result.output
    assert captured and "lsp" in captured[0]


def test_daemon_commands(
    runner: CliRunner, tmp_path: Path, fake_jdk: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    jar = tmp_path / "engine.jar"
    jar.write_bytes(b"x")
    monkeypatch.setenv("JAVA_HOME", str(fake_jdk))
    monkeypatch.setenv("GROOVYSCOPE_JAR", str(jar))
    monkeypatch.setenv("GROOVYSCOPE_SOCKET_DIR", str(tmp_path / "run"))
    monkeypatch.setenv("GROOVYSCOPE_CACHE_DIR", str(tmp_path / "cache"))
    monkeypatch.setenv(cli.CONFIG_ENV, "")
    monkeypatch.setattr(cli.daemon_lifecycle, "ensure_running", lambda *_a, **_k: {"engine": "9.9"})
    monkeypatch.setattr(cli.daemon_lifecycle, "stop", lambda _s: True)
    monkeypatch.setattr(cli.daemon_lifecycle, "probe", lambda _s, _timeout=5.0: {"engine": "9.9", "files": 3})
    assert "engine 9.9" in runner.invoke(cli.main, ["--root", str(tmp_path), "daemon", "start"]).output
    assert "daemon stopped" in runner.invoke(cli.main, ["--root", str(tmp_path), "daemon", "stop"]).output
    status = runner.invoke(cli.main, ["--root", str(tmp_path), "daemon", "status"])
    assert status.exit_code == 0 and "files    3" in status.output

    monkeypatch.setattr(cli.daemon_lifecycle, "probe", lambda _s, _timeout=5.0: None)
    assert runner.invoke(cli.main, ["--root", str(tmp_path), "daemon", "status"]).exit_code == 3
    monkeypatch.setattr(cli.daemon_lifecycle, "stop", lambda _s: False)
    assert "was not running" in runner.invoke(cli.main, ["--root", str(tmp_path), "daemon", "stop"]).output

    monkeypatch.setattr(cli.daemon_lifecycle, "stop", lambda _s: True)
    monkeypatch.setattr(cli.daemon_lifecycle, "probe", lambda _s, _timeout=5.0: {"engine": "9.9"})
    monkeypatch.setattr(
        cli.daemon_lifecycle, "ensure_running", lambda *_a, **_k: {"engine": "9.9", "configHash": "older"}
    )
    restarted = runner.invoke(cli.main, ["--root", str(tmp_path), "daemon", "restart"])
    assert restarted.exit_code == 0 and "daemon restarted" in restarted.output
    assert "started with a different configuration" in restarted.output

    def stop_fails(_s: object) -> bool:
        msg = "did not stop"
        raise RuntimeError(msg)

    monkeypatch.setattr(cli.daemon_lifecycle, "stop", stop_fails)
    assert runner.invoke(cli.main, ["--root", str(tmp_path), "daemon", "restart"]).exit_code == 1

    def failing(*_a: object, **_k: object) -> dict[str, object]:
        msg = "cannot spawn"
        raise RuntimeError(msg)

    monkeypatch.setattr(cli.daemon_lifecycle, "ensure_running", failing)
    assert runner.invoke(cli.main, ["--root", str(tmp_path), "daemon", "start"]).exit_code == 1
    monkeypatch.setattr(cli.daemon_lifecycle, "stop", failing)
    assert runner.invoke(cli.main, ["--root", str(tmp_path), "daemon", "stop"]).exit_code == 1


def test_cds_archive_rules(tmp_path: Path) -> None:
    jdk21 = {"home": "/j", "version": 21, "source": "x"}
    environ = {"GROOVYSCOPE_CACHE_DIR": str(tmp_path)}
    assert (
        cli.cds_archive_for({"daemon": {"cds": True}}, jdk21, "abc" * 30, environ)
        == tmp_path / "cds" / f"engine-{('abc' * 30)[:16]}.jsa"
    )
    assert cli.cds_archive_for({"daemon": {"cds": False}}, jdk21, "abc", environ) is None
    assert (
        cli.cds_archive_for({"daemon": {"cds": True}}, {"home": "/j", "version": 17, "source": "x"}, "abc", environ)
        is None
    )
    assert cli.cds_archive_for({"daemon": {"cds": True}}, jdk21, None, environ) is None


def test_kiro_init_global_merges_into_the_kiro_home(
    runner: CliRunner, tmp_path: Path, isolated_home: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    settings = isolated_home / ".kiro" / "settings"
    settings.mkdir(parents=True)
    (settings / "mcp.json").write_text('{"mcpServers": {"other": {"command": "x"}}}', encoding="utf-8")
    profiles_dir = isolated_home / ".groovyscope" / "profiles"
    profiles_dir.mkdir(parents=True)
    (profiles_dir / "p.yaml").write_text(
        "name: p\nactivation: {markers: [plugin.json]}\nguidance: Stay on the proven path.\n", encoding="utf-8"
    )
    dry = runner.invoke(cli.main, ["--root", str(tmp_path), "kiro", "init", "--global", "--dry-run"])
    assert dry.exit_code == 0, dry.output
    assert f"Kiro home: {isolated_home / '.kiro'}" in dry.output and "dry run: nothing written" in dry.output
    assert not (isolated_home / ".kiro" / "agents").exists()
    real = runner.invoke(cli.main, ["--root", str(tmp_path), "kiro", "init", "--global"])
    assert real.exit_code == 0, real.output
    assert "4 file(s) written" in real.output and "originals kept as <file>.bak" in real.output
    assert "in each Groovy project run `groovyscope kiro init`" in real.output
    mcp = json.loads((settings / "mcp.json").read_text(encoding="utf-8"))
    assert set(mcp["mcpServers"]) == {"other", "groovyscope"}
    steering = (isolated_home / ".kiro" / "steering" / "groovyscope-profiles.md").read_text(encoding="utf-8")
    assert "Stay on the proven path." in steering
    assert not (tmp_path / ".kiro").exists()
    project = runner.invoke(cli.main, ["--root", str(tmp_path), "kiro", "init"])
    assert "writing only the project's LSP entry" in project.output
    assert sorted(p.name for p in (tmp_path / ".kiro").rglob("*") if p.is_file()) == ["lsp.json"]
    full = runner.invoke(cli.main, ["--root", str(tmp_path), "kiro", "repair", "--full"])
    assert full.exit_code == 0 and (tmp_path / ".kiro" / "agents" / "groovy.json").is_file()
    assert not (tmp_path / ".kiro" / "steering" / "groovyscope-profiles.md").exists()
    (tmp_path / "plugin.json").write_text("{}", encoding="utf-8")
    runner.invoke(cli.main, ["--root", str(tmp_path), "kiro", "repair", "--full"])
    assert (tmp_path / ".kiro" / "steering" / "groovyscope-profiles.md").is_file()
    monkeypatch.setenv("KIRO_HOME", str(tmp_path / "kh"))
    repaired = runner.invoke(cli.main, ["--root", str(tmp_path), "kiro", "repair", "--global"])
    assert repaired.exit_code == 0 and (tmp_path / "kh" / "settings" / "mcp.json").is_file()
    assert not (tmp_path / "kh" / "settings" / "lsp.json").exists()
    (tmp_path / ".groovyscope.yaml").write_text("profiles: {discover: maybe}\n", encoding="utf-8")
    assert cli.profile_guidance_for({"root": tmp_path, "config_path": None}) == ""


def test_checkup_and_benchmark_commands(
    runner: CliRunner, tmp_path: Path, isolated_home: Path, clean_env: dict[str, str]
) -> None:
    (tmp_path / "build.gradle").write_text("", encoding="utf-8")
    report = runner.invoke(cli.main, ["--root", str(tmp_path), "checkup"])
    assert report.exit_code == 0, report.output
    assert "fix: groovyscope kiro init --global" in report.output
    agent = runner.invoke(cli.main, ["--root", str(tmp_path), "checkup", "--agent"])
    assert agent.exit_code == 0, agent.output
    assert agent.output.startswith("groovyscope ") and "repaired: Kiro files installed" in agent.output
    assert (isolated_home / ".kiro" / "agents" / "groovy.json").is_file()
    assert (isolated_home / ".groovyscope" / "benchmark" / "codenarc" / "README.md").is_file()
    as_json = runner.invoke(cli.main, ["--root", str(tmp_path), "checkup", "--json"])
    assert json.loads(as_json.output)["repaired"] == []
    path = runner.invoke(cli.main, ["benchmark", "path"])
    assert path.exit_code == 0 and path.output.strip().endswith("benchmark/codenarc")
    elsewhere = runner.invoke(cli.main, ["benchmark", "path", "--to", str(tmp_path / "corpus")])
    assert elsewhere.exit_code == 0 and (tmp_path / "corpus" / "LICENSE.txt").is_file()
    assert "benchmark corpus unpacked" in elsewhere.output


def test_checkup_agent_mode_never_fails(runner: CliRunner, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    def boom(*_a: object, **_k: object) -> object:
        msg = "no"
        raise RuntimeError(msg)

    monkeypatch.setattr(cli.checkup, "build", boom)
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "checkup", "--agent"])
    assert result.exit_code == 0 and result.output.strip() == "groovyscope checkup could not run: no"
    plain = runner.invoke(cli.main, ["--root", str(tmp_path), "checkup"])
    assert plain.exit_code != 0
