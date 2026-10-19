from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Any

import pytest

from groovyscope import daemon


@pytest.fixture(autouse=True)
def long_socket_paths_allowed(monkeypatch: pytest.MonkeyPatch) -> None:
    """pytest's temporary folders exceed sun_path on macOS; the limit itself is tested on its own."""
    monkeypatch.setattr(daemon, "MAX_SOCKET_PATH", 4096)


def test_workspace_key_is_stable_and_short(tmp_path: Path) -> None:
    assert daemon.workspace_key(tmp_path) == daemon.workspace_key(tmp_path / "." / "sub" / "..")
    assert len(daemon.workspace_key(tmp_path)) == 16
    assert daemon.workspace_key(tmp_path) != daemon.workspace_key(tmp_path.parent)


def test_runtime_and_cache_dirs(tmp_path: Path) -> None:
    home = tmp_path / "home"
    assert daemon.runtime_dir({"GROOVYSCOPE_SOCKET_DIR": "/x/run"}, "linux", home) == Path("/x/run")
    assert daemon.runtime_dir({}, "darwin", home) == home / "Library" / "Caches" / "groovyscope" / "run"
    assert daemon.runtime_dir({"XDG_RUNTIME_DIR": "/run/user/1"}, "linux", home) == Path("/run/user/1/groovyscope")
    assert daemon.runtime_dir({}, "linux", home).name.startswith("groovyscope-")
    assert daemon.cache_dir({"GROOVYSCOPE_CACHE_DIR": "/x/cache"}, "linux", home) == Path("/x/cache")
    assert daemon.cache_dir({}, "darwin", home) == home / "Library" / "Caches" / "groovyscope"
    assert daemon.cache_dir({"XDG_CACHE_HOME": "/xc"}, "linux", home) == Path("/xc/groovyscope")
    assert daemon.cache_dir({}, "linux", home) == home / ".cache" / "groovyscope"
    assert daemon.socket_path(tmp_path, {"GROOVYSCOPE_SOCKET_DIR": "/r"}, "linux", home).suffix == ".sock"
    assert daemon.log_path(tmp_path, {"GROOVYSCOPE_CACHE_DIR": "/c"}, "linux", home).parent == Path("/c/daemon")


def test_daemon_command() -> None:
    command = daemon.daemon_command(["java", "-jar", "e.jar"], Path("/s.sock"), 30, Path("/cache"))
    assert command == [
        "java",
        "-jar",
        "e.jar",
        "daemon",
        "--socket",
        "/s.sock",
        "--idle-seconds",
        "1800",
        "--cache-dir",
        "/cache",
    ]
    assert daemon.daemon_command(["java"], Path("/s.sock"), 0, None)[-1] == "60"
    assert daemon.daemon_command(["java"], Path("/s.sock"), 1, None, Path("/ws"))[-2:] == ["--root", "/ws"]


def test_probe_without_socket(tmp_path: Path) -> None:
    assert daemon.probe(tmp_path / "none.sock") is None
    (tmp_path / "dead.sock").write_text("")
    assert daemon.probe(tmp_path / "dead.sock", timeout=1) is None


def test_start_waits_for_the_probe(tmp_path: Path) -> None:
    spawned: list[list[str]] = []
    answers = iter([None, None, {"engine": "1.0"}])

    def spawn(command: list[str], **kwargs: object) -> None:
        spawned.append(command)
        assert kwargs["stdin"] is subprocess.DEVNULL
        assert kwargs["start_new_session"] is True

    status = daemon.start(
        ["java", "daemon"],
        tmp_path / "run" / "s.sock",
        tmp_path / "log" / "d.log",
        {"A": "b"},
        spawn=spawn,
        probe_fn=lambda _p: next(answers),
        sleep=lambda _s: None,
    )
    assert status == {"engine": "1.0"}
    assert spawned == [["java", "daemon"]]
    assert (tmp_path / "log" / "d.log").exists()


def test_start_times_out_with_log_tail(tmp_path: Path) -> None:
    log = tmp_path / "d.log"
    log.write_text("boom: no JDK\n")
    with pytest.raises(RuntimeError, match="boom: no JDK"):
        daemon.start(
            ["java"],
            tmp_path / "s.sock",
            log,
            {},
            spawn=lambda *_a, **_k: None,
            probe_fn=lambda _p: None,
            sleep=lambda _s: None,
            timeout=0,
        )


def test_ensure_running_reuses_a_live_daemon(tmp_path: Path) -> None:
    started: list[object] = []
    status = daemon.ensure_running(
        ["java"],
        tmp_path / "s.sock",
        tmp_path / "l",
        {},
        probe_fn=lambda _p: {"up": 1},
        start_fn=lambda *_a, **_k: started.append(1) or {},
    )
    assert status == {"up": 1}
    assert started == []
    status = daemon.ensure_running(
        ["java"],
        tmp_path / "s.sock",
        tmp_path / "l",
        {},
        probe_fn=lambda _p: None,
        start_fn=lambda *_a, **_k: {"fresh": True},
    )
    assert status == {"fresh": True}


def test_stop(tmp_path: Path) -> None:
    assert daemon.stop(tmp_path / "s.sock", probe_fn=lambda _p: None) is False
    probes = iter([{"up": 1}, {"up": 1}, None])
    notified: list[tuple[Path, str]] = []
    assert (
        daemon.stop(
            tmp_path / "s.sock",
            probe_fn=lambda _p: next(probes),
            notify=lambda path, method: notified.append((path, method)),
            sleep=lambda _s: None,
        )
        is True
    )
    assert notified == [(tmp_path / "s.sock", daemon.SHUTDOWN_METHOD)]
    with pytest.raises(RuntimeError, match="did not stop"):
        daemon.stop(
            tmp_path / "s.sock",
            probe_fn=lambda _p: {"up": 1},
            notify=lambda _p, _m: None,
            sleep=lambda _s: None,
            timeout=0,
        )


def test_info(tmp_path: Path) -> None:
    details = daemon.info(
        tmp_path,
        {"GROOVYSCOPE_SOCKET_DIR": str(tmp_path), "GROOVYSCOPE_CACHE_DIR": str(tmp_path)},
        probe_fn=lambda _p: {"engine": "x"},
    )
    assert details["running"] is True
    assert details["status"] == {"engine": "x"}
    assert details["socket"].endswith(".sock")
    assert (
        daemon.info(tmp_path, {"GROOVYSCOPE_SOCKET_DIR": str(tmp_path)}, probe_fn=lambda _p: None)["running"] is False
    )
    assert details["outOfMemory"] is False
    assert details["heap"] is None and details["restarts"] == 0


def test_info_reads_an_out_of_memory_exit_from_the_log(tmp_path: Path) -> None:
    environ = {"GROOVYSCOPE_SOCKET_DIR": str(tmp_path), "GROOVYSCOPE_CACHE_DIR": str(tmp_path)}
    log_file = daemon.log_path(tmp_path, environ)
    log_file.parent.mkdir(parents=True, exist_ok=True)
    log_file.write_text("x" * 5000 + "\nTerminating due to java.lang.OutOfMemoryError: Java heap space\n")
    dead = daemon.info(tmp_path, environ, probe_fn=lambda _p: None)
    assert dead["running"] is False and dead["outOfMemory"] is True
    assert "daemon.heap" in daemon.out_of_memory_advice(dead["log"])
    # A running daemon is never reported as out of memory, whatever an old log says.
    assert daemon.info(tmp_path, environ, probe_fn=lambda _p: {"engine": "x"})["outOfMemory"] is False
    log_file.write_text("x" * 5000 + "\nOutOfMemoryError long ago\n" + "y" * 3000)
    assert daemon.info(tmp_path, environ, probe_fn=lambda _p: None)["outOfMemory"] is False


def test_memory_helpers() -> None:
    assert daemon.memory_use(None) is None
    assert daemon.memory_use({"memory": "odd"}) is None
    assert daemon.memory_use({"memory": {"usedMb": 1}}) is None
    assert daemon.memory_use({"memory": {"usedMb": "a", "maxMb": 2}}) is None
    assert daemon.memory_use({"memory": {"usedMb": 1, "maxMb": 0}}) is None
    assert daemon.memory_use({"memory": {"usedMb": 100, "maxMb": 1024}}) == (100, 1024)
    assert daemon.memory_summary({"memory": {"usedMb": 100, "maxMb": 1024}}) == "100 of 1024 MB (9%)"
    assert daemon.memory_summary({}) is None
    assert daemon.memory_warning({"memory": {"usedMb": 100, "maxMb": 1024}}) is None
    assert daemon.memory_warning({"memory": {"usedMb": 849, "maxMb": 1000}}) is None
    hot = daemon.memory_warning({"memory": {"usedMb": 850, "maxMb": 1000}})
    assert hot is not None and "850 of 1000 MB (85%)" in hot and "daemon.heap" in hot
    assert daemon.memory_warning({"memory": {"usedMb": 500, "maxMb": 1000}}, ratio=0.5) is not None


def test_socket_path_limit_and_private_dirs(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(daemon, "MAX_SOCKET_PATH", 20)
    with pytest.raises(RuntimeError, match="GROOVYSCOPE_SOCKET_DIR"):
        daemon.check_socket_path(tmp_path / "a-long-socket-name.sock")
    loose = tmp_path / "loose"
    loose.mkdir(mode=0o777)
    loose.chmod(0o777)
    daemon.secure_dir(loose)
    assert (loose.stat().st_mode & 0o777) == 0o700
    created = tmp_path / "new" / "run"
    daemon.secure_dir(created)
    assert (created.stat().st_mode & 0o777) == 0o700
    monkeypatch.setattr(daemon.os, "getuid", lambda: -5)
    with pytest.raises(RuntimeError, match="belongs to another user"):
        daemon.secure_dir(created)


def test_start_fails_fast_when_the_jvm_exits(tmp_path: Path) -> None:
    log = tmp_path / "d.log"
    log.write_text("Error: Could not create the Java Virtual Machine.\n")

    def spawn(_command: list[str], **_kwargs: object) -> Any:
        return type("P", (), {"poll": staticmethod(lambda: 1)})()

    with pytest.raises(RuntimeError, match="exited with code 1"):
        daemon.start(
            ["java"], tmp_path / "s.sock", log, {}, spawn=spawn, probe_fn=lambda _p: None, sleep=lambda _s: None
        )


def test_ensure_running_replaces_a_daemon_of_another_engine_version(tmp_path: Path) -> None:
    states = iter([{"engine": "0.6.2"}])
    stopped: list[Path] = []
    started: list[str] = []

    def start_fn(*_a: object, **_k: object) -> dict[str, Any]:
        started.append("new")
        return {"engine": "0.8.0"}

    status = daemon.ensure_running(
        ["java"],
        tmp_path / "s.sock",
        tmp_path / "d.log",
        {},
        probe_fn=lambda _p: next(states, None),
        start_fn=start_fn,
        stop_fn=lambda p: stopped.append(p) or True,
        expected_engine="0.8.0",
    )
    assert status == {"engine": "0.8.0"} and stopped == [tmp_path / "s.sock"] and started == ["new"]
    same = daemon.ensure_running(
        ["java"],
        tmp_path / "s.sock",
        tmp_path / "d.log",
        {},
        probe_fn=lambda _p: {"engine": "0.8.0"},
        start_fn=start_fn,
        expected_engine="0.8.0",
    )
    assert same == {"engine": "0.8.0"} and started == ["new"]


def test_probe_state_tells_the_four_states_apart(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    assert daemon.probe_state(tmp_path / "none.sock") == daemon.PROBE_ABSENT
    socket_file = tmp_path / "live.sock"
    socket_file.write_text("")
    answers: dict[str, object] = {}

    def fake_request(_path: Path, _method: str, _params: object, timeout: float) -> object:
        answer = answers["value"]
        if isinstance(answer, BaseException):
            raise answer
        return answer

    monkeypatch.setattr(daemon.rpc, "request", fake_request)
    answers["value"] = TimeoutError("slow")
    assert daemon.probe_state(socket_file) == daemon.PROBE_BUSY
    answers["value"] = ConnectionRefusedError("nobody")
    assert daemon.probe_state(socket_file) == daemon.PROBE_ABSENT
    answers["value"] = RuntimeError("method not found")
    assert daemon.probe_state(socket_file) == daemon.PROBE_REFUSED
    answers["value"] = {"hello": "world"}
    assert daemon.probe_state(socket_file) == daemon.PROBE_REFUSED
    answers["value"] = {"engine": "1.0.0"}
    assert daemon.probe_state(socket_file) == daemon.PROBE_RUNNING


def test_ensure_running_does_not_start_a_second_jvm_against_a_busy_daemon(tmp_path: Path) -> None:
    started: list[object] = []
    status = daemon.ensure_running(
        ["java"],
        tmp_path / "busy.sock",
        tmp_path / "busy.log",
        {},
        probe_fn=lambda _p: None,
        start_fn=lambda *a, **_k: started.append(a) or {"engine": "x"},
        state_fn=lambda _p: daemon.PROBE_BUSY,
    )
    assert status == {"busy": True} and started == []
