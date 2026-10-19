"""Classpath state and the in-place configuration reload: fingerprints, status, the MCP reload and refresh."""

from __future__ import annotations

import json
import os
import threading
import time
from collections.abc import Mapping
from pathlib import Path
from typing import Any

import pytest
from click.testing import CliRunner

from groovyscope import classpath, cli, mcp_server, trust


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def backdate(path: Path, seconds: float = 100.0) -> None:
    stamp = time.time() - seconds
    os.utime(path, (stamp, stamp))


EXPORT: classpath.Export = {
    "tool": "gradle",
    "command": ["gradlew", "-q"],
    "paths": ["/a.jar", "/b.jar"],
    "missing": [],
}


def test_build_files_cover_nested_modules_and_skip_build_output(tmp_path: Path) -> None:
    for name in ("settings.gradle", "build.gradle", "gradle/libs.versions.toml", "app/build.gradle.kts", "lib/pom.xml"):
        write(tmp_path / name, "")
    write(tmp_path / "build/build.gradle", "")  # generated: ignored
    write(tmp_path / "node_modules/x/build.gradle", "")
    write(tmp_path / "a/b/c/d/e/build.gradle", "")  # deeper than the fingerprint depth
    found = [p.relative_to(tmp_path).as_posix() for p in classpath.build_files(tmp_path)]
    assert found == [
        "settings.gradle",
        "build.gradle",
        "gradle/libs.versions.toml",
        "app/build.gradle.kts",
        "lib/pom.xml",
    ]


def test_fingerprint_changes_with_any_build_file(tmp_path: Path) -> None:
    write(tmp_path / "build.gradle", "plugins {}\n")
    write(tmp_path / "core/build.gradle", "dependencies {}\n")
    first = classpath.build_fingerprint(tmp_path)
    assert first == classpath.build_fingerprint(tmp_path)
    write(tmp_path / "core/build.gradle", "dependencies { implementation 'x:y:1' }\n")
    assert classpath.build_fingerprint(tmp_path) != first
    assert classpath.build_fingerprint(tmp_path / "nowhere") == classpath.build_fingerprint(tmp_path / "elsewhere")


def test_classpath_file_records_the_fingerprint_and_status_reads_it(tmp_path: Path) -> None:
    write(tmp_path / "build.gradle", "plugins {}\n")
    target = tmp_path / ".groovyscope/classpath.txt"
    missing = classpath.status(tmp_path, target)
    assert (
        missing["state"] == "missing" and missing["fix"] == "groovyscope classpath" and "gradle" in missing["message"]
    )
    classpath.write_file(target, EXPORT, fingerprint=classpath.build_fingerprint(tmp_path))
    assert classpath.read_fingerprint(target) == classpath.build_fingerprint(tmp_path)
    assert classpath.count_entries(target) == 2
    fresh = classpath.status(tmp_path, target)
    assert fresh == {"state": "fresh", "entries": 2, "message": "2 entries from classpath.txt"}
    write(tmp_path / "build.gradle", "plugins { id 'groovy' }\n")
    stale = classpath.status(tmp_path, target)
    assert stale["state"] == "stale" and stale["fix"] == "groovyscope classpath"
    assert "new dependencies, not defects" in stale["message"]
    assert classpath.status(tmp_path, tmp_path / "none.txt", configured=True)["state"] == "configured"
    assert classpath.status(tmp_path / "empty", tmp_path / "empty/none.txt")["state"] == "none"


def test_legacy_classpath_file_without_fingerprint_uses_modification_times(tmp_path: Path) -> None:
    write(tmp_path / "build.gradle", "plugins {}\n")
    target = tmp_path / ".groovyscope/classpath.txt"
    write(target, "# produced by: gradle\n/a.jar\n")
    assert classpath.read_fingerprint(target) is None
    backdate(target)
    assert classpath.status(tmp_path, target)["state"] == "stale"
    backdate(tmp_path / "build.gradle", 200.0)
    assert classpath.status(tmp_path, target)["state"] == "fresh"


def test_write_file_keeps_the_usual_permissions_and_no_temporary(tmp_path: Path) -> None:
    target = tmp_path / ".groovyscope/classpath.txt"
    classpath.write_file(target, EXPORT)
    assert target.stat().st_mode & 0o444 == 0o444
    assert [p.name for p in target.parent.iterdir()] == ["classpath.txt"]


def test_session_caller_reloads_the_configuration_when_its_hash_changes(monkeypatch: pytest.MonkeyPatch) -> None:
    sent: list[tuple[str, Any]] = []

    def fake_call(_session: Any, method: str, params: Any = None, timeout: float | None = None) -> Any:
        sent.append((method, params))
        return {"configHash": "h", "reloaded": True} if method == mcp_server.RELOAD_METHOD else {"ok": True}

    monkeypatch.setattr(mcp_server.rpc, "channel_call", fake_call)
    current: list[tuple[str, str] | None] = [("h1", '{"a": 1}')]
    caller, _ = mcp_server.session_caller(
        Path("/a.sock"), None, open_session=lambda _p: {}, configuration=lambda: current[0]
    )
    caller("groovyscope/check", {})
    caller("groovyscope/check", {})
    assert [m for m, _ in sent] == [mcp_server.RELOAD_METHOD, "groovyscope/check", "groovyscope/check"]
    assert sent[0][1] == {"config": '{"a": 1}'}
    current[0] = ("h2", '{"a": 2}')
    caller("groovyscope/symbols", {})
    assert [m for m, _ in sent][3:] == [mcp_server.RELOAD_METHOD, "groovyscope/symbols"]
    current[0] = None  # the configuration cannot be loaded: the engine keeps the last good one
    caller("groovyscope/symbols", {})
    assert sent[-1][0] == "groovyscope/symbols" and sent[-2][0] == "groovyscope/symbols"


def test_session_caller_keeps_serving_when_the_engine_refuses_a_configuration(monkeypatch: pytest.MonkeyPatch) -> None:
    def fake_call(_session: Any, method: str, params: Any = None, timeout: float | None = None) -> Any:
        if method == mcp_server.RELOAD_METHOD:
            msg = "reloadConfig failed: configuration not accepted"
            raise RuntimeError(msg)
        return {"ok": method}

    monkeypatch.setattr(mcp_server.rpc, "channel_call", fake_call)
    caller, _ = mcp_server.session_caller(
        Path("/a.sock"), None, open_session=lambda _p: {}, configuration=lambda: ("h", "{")
    )
    assert caller("groovyscope/check", {}) == {"ok": "groovyscope/check"}


def test_session_caller_reports_a_timeout_instead_of_resending(monkeypatch: pytest.MonkeyPatch) -> None:
    attempts: list[str] = []

    def slow(_session: Any, method: str, params: Any = None, timeout: float | None = None) -> Any:
        attempts.append(method)
        raise TimeoutError

    monkeypatch.setattr(mcp_server.rpc, "channel_call", slow)
    reconnects: list[int] = []
    caller, _ = mcp_server.session_caller(
        Path("/a.sock"), lambda: reconnects.append(1) or Path("/b.sock"), open_session=lambda _p: {}
    )
    with pytest.raises(RuntimeError, match="did not answer within 100s"):
        caller("groovyscope/duplicates", {})
    assert attempts == ["groovyscope/duplicates"] and not reconnects


def test_session_caller_reconnects_after_a_framing_error(monkeypatch: pytest.MonkeyPatch) -> None:
    answers = iter([ValueError("frame too large"), {"ok": True}])

    def fake_call(_session: Any, method: str, params: Any = None, timeout: float | None = None) -> Any:
        answer = next(answers)
        if isinstance(answer, Exception):
            raise answer
        return answer

    monkeypatch.setattr(mcp_server.rpc, "channel_call", fake_call)
    monkeypatch.setattr(mcp_server.rpc, "close_channel", lambda _s: None)
    caller, _ = mcp_server.session_caller(Path("/a.sock"), lambda: Path("/b.sock"), open_session=lambda p: {"p": p})
    assert caller("groovyscope/check", {}) == {"ok": True}


def classpath_tools(tmp_path: Path, engine: dict[str, Any], **settings: Any) -> dict[str, Any]:
    def call(method: str, params: dict[str, Any]) -> Any:
        engine["calls"].append((method, params))
        return engine["responses"].get(method, {})

    output = {
        "maxResults": 3,
        "maxBytes": 16000,
        "classpath": {"file": str(tmp_path / ".groovyscope/classpath.txt"), "configured": False, **settings},
    }
    return mcp_server.build_tools(tmp_path, call, output, {})


def test_check_and_status_report_a_stale_classpath(tmp_path: Path, engine: dict[str, Any]) -> None:
    write(tmp_path / "build.gradle", "plugins {}\n")
    engine["responses"]["groovyscope/check"] = {"diagnostics": {}, "summary": {}}
    engine["responses"]["groovyscope/status"] = {"engine": "1"}
    tools = classpath_tools(tmp_path, engine, trusted=True)
    assert tools["check"]()["classpath"]["state"] == "missing"
    classpath.write_file(
        tmp_path / ".groovyscope/classpath.txt", EXPORT, fingerprint=classpath.build_fingerprint(tmp_path)
    )
    assert "classpath" not in tools["check"]()
    assert tools["workspace_status"]()["classpath"]["state"] == "fresh"
    write(tmp_path / "build.gradle", "plugins { id 'groovy' }\n")
    checked = tools["check"]()
    assert checked["classpath"]["state"] == "stale" and checked["classpath"]["fix"] == "groovyscope classpath"


def test_refresh_classpath_runs_the_export_only_in_a_trusted_workspace(
    tmp_path: Path, engine: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    write(tmp_path / "build.gradle", "plugins {}\n")
    untrusted = classpath_tools(tmp_path, engine, trusted=False)
    assert "groovyscope trust" in untrusted["refresh_classpath"]()["error"]
    seen: list[dict[str, Any]] = []

    def fake_export(root: Path, environ: Mapping[str, str], **kwargs: Any) -> classpath.Export:
        seen.append({"root": root, "java": environ.get("JAVA_HOME"), **kwargs})
        return EXPORT

    monkeypatch.setattr(mcp_server.classpath_export, "export", fake_export)
    trusted = classpath_tools(tmp_path, engine, trusted=True, javaHome="/jdk")
    done = trusted["refresh_classpath"](timeout=5)
    assert done == {
        "tool": "gradle",
        "entries": 2,
        "missing": 0,
        "file": ".groovyscope/classpath.txt",
        "note": "the engine reloads the classpath on its next request",
    }
    runner = seen[0].pop("runner")
    assert seen == [{"root": tmp_path, "java": "/jdk", "timeout": 5}]
    assert runner.func is classpath.default_runner and callable(runner.keywords["on_wait"])
    assert classpath.status(tmp_path, tmp_path / ".groovyscope/classpath.txt")["state"] == "fresh"

    def failing(*_a: Any, **_k: Any) -> classpath.Export:
        msg = "gradlew failed (exit 1)"
        raise ValueError(msg)

    monkeypatch.setattr(mcp_server.classpath_export, "export", failing)
    assert trusted["refresh_classpath"]()["error"] == "gradlew failed (exit 1)"
    assert "run `groovyscope classpath`" in classpath_tools(tmp_path, engine, file="")["refresh_classpath"]()["error"]


def test_cli_reloads_a_daemon_running_an_older_configuration(
    runner: CliRunner, tmp_path: Path, fake_jdk: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    jar = tmp_path / "engine.jar"
    jar.write_bytes(b"x")
    monkeypatch.setenv("JAVA_HOME", str(fake_jdk))
    monkeypatch.setenv("GROOVYSCOPE_JAR", str(jar))
    monkeypatch.setattr(
        cli.daemon_lifecycle, "ensure_running", lambda *_a, **_k: {"engine": "9.9", "configHash": "old"}
    )
    sent: list[tuple[str, dict[str, Any]]] = []

    def rpc(_socket: Path, method: str, params: dict[str, Any], _timeout: float) -> Any:
        sent.append((method, params))
        return {"configHash": "new", "reloaded": True} if method == cli.RELOAD_METHOD else {}

    monkeypatch.setattr(cli, "_RPC", rpc)
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "daemon", "restart"])
    assert result.exit_code == 0, result.output
    assert sent[0][0] == cli.RELOAD_METHOD and json.loads(sent[0][1]["config"])["root"] == str(tmp_path)
    assert "older configuration" not in result.output


def test_config_source_recomputes_only_when_the_files_change(tmp_path: Path, clean_env: dict[str, str]) -> None:
    obj = {"root": tmp_path, "config_path": None}
    current = cli.config_source(obj)
    first = current()
    assert first is not None and json.loads(first[1])["workspace"]["classpath"] == []
    assert current() is first  # memoised: nothing changed on disk
    write(tmp_path / ".groovyscope/classpath.txt", "/dep.jar\n")
    (tmp_path / "dep.jar").write_bytes(b"x")
    write(tmp_path / ".groovyscope/classpath.txt", f"{tmp_path / 'dep.jar'}\n")
    second = current()
    assert second is not None and second[0] != first[0]
    assert json.loads(second[1])["workspace"]["classpath"] == [str(tmp_path / "dep.jar")]
    write(tmp_path / ".groovyscope.yaml", "workspace: [not a mapping\n")
    assert current() is second  # unloadable configuration: the last good one stays


def test_classpath_command_reloads_the_running_daemon_in_place(
    runner: CliRunner, tmp_path: Path, clean_env: dict[str, str], isolated_home: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    (tmp_path / "settings.gradle").write_text("", encoding="utf-8")
    trust.set_trusted(tmp_path, isolated_home, trusted=True)
    monkeypatch.setattr(cli.classpath_export, "export", lambda *_a, **_k: EXPORT)
    monkeypatch.setattr(cli.daemon_lifecycle, "probe", lambda _s, _t=5.0: {"engine": "x", "configHash": "old"})
    stopped: list[Path] = []
    monkeypatch.setattr(cli.daemon_lifecycle, "stop", lambda s: stopped.append(s) or True)
    monkeypatch.setattr(cli, "_RPC", lambda _s, _m, _p, _t: {"configHash": "new", "reloaded": True})
    done = runner.invoke(cli.main, ["--root", str(tmp_path), "classpath"])
    assert done.exit_code == 0, done.output
    assert "reloaded the new classpath" in done.output and not stopped
    assert classpath.read_fingerprint(tmp_path / ".groovyscope/classpath.txt") == classpath.build_fingerprint(tmp_path)

    def refusing(*_a: Any, **_k: Any) -> Any:
        msg = "reloadConfig failed"
        raise RuntimeError(msg)

    monkeypatch.setattr(cli, "_RPC", refusing)
    fallback = runner.invoke(cli.main, ["--root", str(tmp_path), "classpath"])
    assert fallback.exit_code == 0 and "was stopped" in fallback.output and stopped


def test_a_configuration_push_on_a_dead_channel_reconnects_then_applies() -> None:
    sent: list[tuple[str, str]] = []

    def call(session: dict[str, Any], method: str, _params: Any = None, timeout: float | None = None) -> Any:
        sent.append((session["name"], method))
        if session["name"] == "dead":
            msg = "engine closed the session"
            raise ConnectionError(msg)
        return {"reloaded": True} if method == mcp_server.RELOAD_METHOD else {"ok": method}

    names = iter(["dead", "fresh"])
    caller, _ = mcp_server.session_caller(
        Path("/a.sock"),
        lambda: Path("/b.sock"),
        open_session=lambda _p: {"name": next(names)},
        configuration=lambda: ("h1", "{}"),
        call_fn=call,
        close_fn=lambda _s: None,
    )
    assert caller("groovyscope/check", {}) == {"ok": "groovyscope/check"}
    assert sent == [
        ("dead", mcp_server.RELOAD_METHOD),
        ("fresh", mcp_server.RELOAD_METHOD),
        ("fresh", "groovyscope/check"),
    ]


def test_a_slow_configuration_reload_does_not_hold_other_calls() -> None:
    reloading = threading.Event()
    release = threading.Event()

    def call(_session: dict[str, Any], method: str, _params: Any = None, timeout: float | None = None) -> Any:
        if method == mcp_server.RELOAD_METHOD:
            reloading.set()
            release.wait(10)
            return {"reloaded": True}
        return {"ok": method}

    caller, _ = mcp_server.session_caller(
        Path("/a.sock"),
        None,
        open_session=lambda _p: {},
        configuration=lambda: ("h1", "{}"),
        call_fn=call,
        close_fn=lambda _s: None,
    )
    first: dict[str, Any] = {}
    worker = threading.Thread(target=lambda: first.update(answer=caller("groovyscope/check", {})))
    worker.start()
    assert reloading.wait(5)
    second: dict[str, Any] = {}
    other = threading.Thread(target=lambda: second.update(answer=caller("groovyscope/status", {})))
    other.start()
    try:
        other.join(3)
        assert second.get("answer") == {"ok": "groovyscope/status"}  # answered while the reload still waits
    finally:
        release.set()
        worker.join(5)
    assert first["answer"] == {"ok": "groovyscope/check"}
