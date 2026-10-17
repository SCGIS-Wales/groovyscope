"""Lifecycle of the warm engine daemon: socket and cache locations, start, probe, stop."""

from __future__ import annotations

import contextlib
import hashlib
import logging
import os
import stat
import subprocess
import sys
import tempfile
import time
from collections.abc import Callable, Iterator, Mapping, Sequence
from pathlib import Path
from typing import Any, TypedDict

from groovyscope import rpc

SOCKET_DIR_ENV = "GROOVYSCOPE_SOCKET_DIR"
CACHE_DIR_ENV = "GROOVYSCOPE_CACHE_DIR"
STATUS_METHOD = "groovyscope/status"
SHUTDOWN_METHOD = "groovyscope/shutdownDaemon"
START_TIMEOUT_SECONDS = 60.0
STOP_TIMEOUT_SECONDS = 15.0
# sun_path is 104 bytes on macOS and the BSDs, 108 on Linux (including the terminating NUL).
MAX_SOCKET_PATH = {"darwin": 103}.get(sys.platform, 107)
LOG = logging.getLogger("groovyscope.daemon")

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


def secure_dir(path: Path) -> None:
    """Create ``path`` (and parents) private to this user, or refuse one another user could tamper with: the
    daemon's socket and log live there, and a planted socket could feed edits to every client."""
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    if not hasattr(os, "getuid"):
        return
    info = path.stat()
    if info.st_uid != os.getuid():
        msg = f"{path} belongs to another user; set GROOVYSCOPE_SOCKET_DIR to a directory you own"
        raise RuntimeError(msg)
    if stat.S_IMODE(info.st_mode) & 0o077:
        LOG.debug("tightening permissions of %s to 0700", path)
        path.chmod(0o700)


def check_socket_path(socket_file: Path) -> None:
    """Fail clearly instead of timing out when the path is too long for a Unix domain socket."""
    if len(os.fsencode(str(socket_file))) > MAX_SOCKET_PATH:
        msg = (
            f"socket path {socket_file} is longer than {MAX_SOCKET_PATH} bytes; "
            "set GROOVYSCOPE_SOCKET_DIR to a shorter directory"
        )
        raise RuntimeError(msg)


@contextlib.contextmanager
def start_lock(socket_file: Path) -> Iterator[None]:
    """Serialise probe-and-start across processes, so two clients never start two daemons for one root."""
    try:
        import fcntl  # noqa: PLC0415 - POSIX only; Unix domain sockets are too
    except ImportError:  # pragma: no cover - non-POSIX
        yield
        return
    lock_file = socket_file.with_name(socket_file.name + ".lock")
    with lock_file.open("a") as handle:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


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
    """Spawn the daemon detached and wait until it answers on the socket. Returns its status. Fails as soon as
    the JVM exits instead of waiting for the timeout."""
    check = _probe(probe_fn)
    check_socket_path(socket_file)
    secure_dir(socket_file.parent)
    secure_dir(log_file.parent)
    LOG.debug("starting daemon: %s", " ".join(command))
    with log_file.open("ab") as log:
        process = spawn(
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
            LOG.debug("daemon answered on %s: %s", socket_file, status)
            return status
        exited = getattr(process, "poll", lambda: None)()
        if exited is not None or time.monotonic() >= deadline:
            tail = log_file.read_text(encoding="utf-8", errors="replace")[-2000:] if log_file.exists() else ""
            reason = f"exited with code {exited}" if exited is not None else f"did not answer within {timeout:.0f}s"
            msg = f"daemon on {socket_file} {reason}; log {log_file} ends with:\n{tail}"
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
    stop_fn: Callable[[Path], bool] | None = None,
    expected_engine: str | None = None,
) -> dict[str, Any]:
    """Probe first; start only when nothing answers. A daemon running another engine version (left over from
    before an upgrade) is stopped and replaced. Probe and start run under a lock, so concurrent clients share
    one daemon."""
    check = _probe(probe_fn)
    launch = start if start_fn is None else start_fn
    halt = stop if stop_fn is None else stop_fn
    check_socket_path(socket_file)
    secure_dir(socket_file.parent)
    with start_lock(socket_file):
        status = check(socket_file)
        if status is not None and expected_engine and status.get("engine") not in (None, expected_engine):
            LOG.info("daemon runs engine %s, expected %s; restarting it", status.get("engine"), expected_engine)
            halt(socket_file)
            status = None
        if status is not None:
            return status
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
