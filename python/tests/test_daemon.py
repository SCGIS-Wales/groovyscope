from __future__ import annotations

import subprocess
from pathlib import Path

import pytest

from groovyscope import daemon


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
