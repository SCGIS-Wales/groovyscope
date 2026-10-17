"""Lifecycle of the warm engine daemon: socket and cache locations, start, probe, stop."""

from __future__ import annotations

import hashlib
import os
import subprocess
import sys
import tempfile
import time
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any, TypedDict

from groovyscope import rpc

SOCKET_DIR_ENV = "GROOVYSCOPE_SOCKET_DIR"
CACHE_DIR_ENV = "GROOVYSCOPE_CACHE_DIR"
STATUS_METHOD = "groovyscope/status"
SHUTDOWN_METHOD = "groovyscope/shutdownDaemon"
START_TIMEOUT_SECONDS = 60.0
STOP_TIMEOUT_SECONDS = 15.0

Spawn = Callable[..., Any]
Probe = Callable[[Path], "dict[str, Any] | None"]


def _probe(probe_fn: Probe | None) -> Probe:
    return probe if probe_fn is None else probe_fn


class DaemonInfo(TypedDict):
    """What ``daemon status`` reports."""

    socket: str
    running: bool
    status: dict[str, Any] | None
    log: str


def workspace_key(root: Path) -> str:
    """Sixteen hex digits of the SHA-256 of the absolute root; matches the engine's workspaceKey."""
    return hashlib.sha256(str(root.resolve()).encode("utf-8")).hexdigest()[:16]


def runtime_dir(environ: Mapping[str, str], platform: str = sys.platform, home: Path | None = None) -> Path:
    """Where sockets live: ``GROOVYSCOPE_SOCKET_DIR``, else a per-user runtime directory."""
    explicit = environ.get(SOCKET_DIR_ENV, "")
    if explicit:
        return Path(explicit).expanduser()
    base = home or Path.home()
    if platform == "darwin":
        return base / "Library" / "Caches" / "groovyscope" / "run"
    xdg = environ.get("XDG_RUNTIME_DIR", "")
    if xdg:
        return Path(xdg) / "groovyscope"
    return Path(tempfile.gettempdir()) / f"groovyscope-{os.getuid() if hasattr(os, 'getuid') else 'user'}"


def cache_dir(environ: Mapping[str, str], platform: str = sys.platform, home: Path | None = None) -> Path:
    """Where symbol caches, CDS archives and daemon logs live."""
    explicit = environ.get(CACHE_DIR_ENV, "")
    if explicit:
        return Path(explicit).expanduser()
    base = home or Path.home()
    if platform == "darwin":
        return base / "Library" / "Caches" / "groovyscope"
    xdg = environ.get("XDG_CACHE_HOME", "")
    return (Path(xdg) if xdg else base / ".cache") / "groovyscope"


def socket_path(root: Path, environ: Mapping[str, str], platform: str = sys.platform, home: Path | None = None) -> Path:
    return runtime_dir(environ, platform, home) / f"{workspace_key(root)}.sock"


def log_path(root: Path, environ: Mapping[str, str], platform: str = sys.platform, home: Path | None = None) -> Path:
    return cache_dir(environ, platform, home) / "daemon" / f"{workspace_key(root)}.log"


def probe(socket_file: Path, timeout: float = 5.0) -> dict[str, Any] | None:
    """The daemon's status, or None when nothing answers on the socket."""
    if not socket_file.exists():
        return None
    try:
        result = rpc.request(socket_file, STATUS_METHOD, {}, timeout=timeout)
    except (OSError, RuntimeError, ValueError):
        return None
    return result if isinstance(result, dict) else {}


def daemon_command(
    engine_command: Sequence[str], socket_file: Path, idle_minutes: int, cache: Path | None
) -> list[str]:
    """The engine invocation for ``daemon`` mode, appended to the JVM command line."""
    command = [
        *engine_command,
        "daemon",
        "--socket",
        str(socket_file),
        "--idle-seconds",
        str(max(1, idle_minutes) * 60),
    ]
    if cache is not None:
        command += ["--cache-dir", str(cache)]
    return command


def start(
    command: Sequence[str],
    socket_file: Path,
    log_file: Path,
    environ: Mapping[str, str],
    *,
    spawn: Spawn = subprocess.Popen,
    probe_fn: Probe | None = None,
    sleep: Callable[[float], None] = time.sleep,
    timeout: float = START_TIMEOUT_SECONDS,
) -> dict[str, Any]:
    """Spawn the daemon detached and wait until it answers on the socket. Returns its status."""
    check = _probe(probe_fn)
    socket_file.parent.mkdir(parents=True, exist_ok=True)
    log_file.parent.mkdir(parents=True, exist_ok=True)
    with log_file.open("ab") as log:
        spawn(
            list(command),
            stdin=subprocess.DEVNULL,
            stdout=log,
            stderr=subprocess.STDOUT,
            env=dict(environ),
            start_new_session=True,
            close_fds=True,
        )
    deadline = time.monotonic() + timeout
    while True:
        status = check(socket_file)
        if status is not None:
            return status
        if time.monotonic() >= deadline:
            tail = log_file.read_text(encoding="utf-8", errors="replace")[-2000:] if log_file.exists() else ""
            msg = f"daemon did not answer on {socket_file} within {timeout:.0f}s; log tail:\n{tail}"
            raise RuntimeError(msg)
        sleep(0.2)


def ensure_running(
    command: Sequence[str],
    socket_file: Path,
    log_file: Path,
    environ: Mapping[str, str],
    *,
    probe_fn: Probe | None = None,
    start_fn: Callable[..., dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Probe first; start only when nothing answers."""
    check = _probe(probe_fn)
    status = check(socket_file)
    if status is not None:
        return status
    launch = start if start_fn is None else start_fn
    return launch(command, socket_file, log_file, environ, probe_fn=check)


def stop(
    socket_file: Path,
    *,
    probe_fn: Probe | None = None,
    notify: Callable[[Path, str], None] = rpc.notify,
    sleep: Callable[[float], None] = time.sleep,
    timeout: float = STOP_TIMEOUT_SECONDS,
) -> bool:
    """Ask a running daemon to exit and wait for its socket to go away. False when it was not running."""
    check = _probe(probe_fn)
    if check(socket_file) is None:
        return False
    notify(socket_file, SHUTDOWN_METHOD)
    deadline = time.monotonic() + timeout
    while check(socket_file) is not None:
        if time.monotonic() >= deadline:
            msg = f"daemon on {socket_file} did not stop within {timeout:.0f}s"
            raise RuntimeError(msg)
        sleep(0.2)
    return True


def info(root: Path, environ: Mapping[str, str], *, probe_fn: Probe | None = None) -> DaemonInfo:
    socket_file = socket_path(root, environ)
    status = _probe(probe_fn)(socket_file)
    return {
        "socket": str(socket_file),
        "running": status is not None,
        "status": status,
        "log": str(log_path(root, environ)),
    }
