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

from groovyscope import rpc, selfheal

SOCKET_DIR_ENV = "GROOVYSCOPE_SOCKET_DIR"
CACHE_DIR_ENV = "GROOVYSCOPE_CACHE_DIR"
STATUS_METHOD = "groovyscope/status"
SHUTDOWN_METHOD = "groovyscope/shutdownDaemon"
START_TIMEOUT_SECONDS = 60.0
STOP_TIMEOUT_SECONDS = 15.0
LOCK_TIMEOUT_SECONDS = START_TIMEOUT_SECONDS + STOP_TIMEOUT_SECONDS  # one holder may stop an old daemon, then start
LOCK_POLL_SECONDS = 0.1
# sun_path is 104 bytes on macOS and the BSDs, 108 on Linux (including the terminating NUL).
MAX_SOCKET_PATH = {"darwin": 103}.get(sys.platform, 107)
LOG = logging.getLogger("groovyscope.daemon")
# What the JVM prints when `-XX:+ExitOnOutOfMemoryError` ends the daemon (`Terminating due to
# java.lang.OutOfMemoryError: Java heap space`); any other OutOfMemoryError in the log tail counts too.
OOM_MARKER = "OutOfMemoryError"
# `doctor` and `checkup` warn once the heap in use passes this share of the maximum.
HEAP_WARN_RATIO = 0.85
HEAP_FIX = "raise `daemon.heap` in `.groovyscope.yaml` (then `groovyscope daemon restart`)"

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
    outOfMemory: bool  # the daemon is not running and its log ends with an OutOfMemoryError
    heap: str | None  # the heap the last start used, when the self-healing grew it past daemon.heap
    restarts: int  # daemon starts in the last ten minutes


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


def client_path(root: Path, environ: Mapping[str, str], platform: str = sys.platform, home: Path | None = None) -> Path:
    """Where the MCP server records the client that last connected for this workspace (name and capabilities)."""
    return cache_dir(environ, platform, home) / "daemon" / f"{workspace_key(root)}.client.json"


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
def start_lock(
    socket_file: Path,
    *,
    timeout: float = LOCK_TIMEOUT_SECONDS,
    sleep: Callable[[float], None] = time.sleep,
    clock: Callable[[], float] = time.monotonic,
) -> Iterator[None]:
    """Serialise probe-and-start (and stop) across processes, so two clients never start two daemons for one root.
    The wait is bounded: a holder that takes longer than ``timeout`` (a start and a stop together) is stuck, and
    the caller gets a RuntimeError instead of hanging with it."""
    try:
        import fcntl  # noqa: PLC0415 - POSIX only; Unix domain sockets are too
    except ImportError:  # pragma: no cover - non-POSIX
        yield
        return
    lock_file = socket_file.with_name(socket_file.name + ".lock")
    with lock_file.open("a") as handle:
        deadline = clock() + timeout
        while True:
            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if clock() >= deadline:
                    msg = f"another groovyscope process has been starting or stopping the daemon on {socket_file} "
                    msg += f"for over {timeout:.0f}s; try again, or `groovyscope daemon stop`"
                    raise RuntimeError(msg) from None
                sleep(LOCK_POLL_SECONDS)
        try:
            yield
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


PROBE_RUNNING = "running"
PROBE_ABSENT = "absent"  # no socket, or nothing listens on it
PROBE_BUSY = "busy"  # the socket accepted the connection but the status did not come back in time
PROBE_REFUSED = "refused"  # something answered, but not a groovyscope status (another engine, a broken frame)
State = Callable[[Path], str]


def probe_state(socket_file: Path, timeout: float = 5.0) -> str:
    """What the socket told us: ``running``, ``absent``, ``busy`` or ``refused``. A busy daemon is alive, so a
    caller must wait or retry rather than start a second JVM against the same socket."""
    if not socket_file.exists():
        return PROBE_ABSENT
    try:
        result = rpc.request(socket_file, STATUS_METHOD, {}, timeout=timeout)
    except TimeoutError:
        return PROBE_BUSY
    except OSError:
        return PROBE_ABSENT
    except (RuntimeError, ValueError):
        return PROBE_REFUSED
    return PROBE_RUNNING if isinstance(result, dict) and "engine" in result else PROBE_REFUSED


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
    engine_command: Sequence[str],
    socket_file: Path,
    idle_minutes: int,
    cache: Path | None,
    root: Path | None = None,
    *,
    config_file: Path | None = None,
) -> list[str]:
    """The engine invocation for ``daemon`` mode, appended to the JVM command line. With ``root``, the daemon serves
    that workspace only (its configuration and trust decision are the ones it was started with)."""
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
    if root is not None:
        command += ["--root", str(root)]
    if config_file is not None:
        command += ["--config", str(config_file)]
    return command


LOG_TAIL_BYTES = 2000


def log_tail(log_file: Path, limit: int = LOG_TAIL_BYTES) -> str:
    """The last ``limit`` bytes of a log file, without reading the whole file."""
    try:
        with log_file.open("rb") as handle:
            handle.seek(0, os.SEEK_END)
            size = handle.tell()
            handle.seek(max(0, size - limit))
            return handle.read().decode("utf-8", errors="replace")
    except OSError:
        return ""


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
            if exited is None:
                # Not answering yet, and a later bind would race the next start: stop it now.
                with contextlib.suppress(OSError):
                    getattr(process, "kill", lambda: None)()
            reason = f"exited with code {exited}" if exited is not None else f"did not answer within {timeout:.0f}s"
            msg = f"daemon on {socket_file} {reason}; log {log_file} ends with:\n{log_tail(log_file)}"
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
    state_fn: State | None = None,
    settings: Mapping[str, Any] | None = None,
    sleep: Callable[[float], None] = time.sleep,
    now: Callable[[], float] = selfheal.clock,
) -> dict[str, Any]:
    """Probe first; start only when nothing answers. A daemon running another engine version (left over from
    before an upgrade) is stopped and replaced. A daemon that is alive but too busy to answer the probe is left
    alone: the caller gets ``{"busy": True}`` and connects to it anyway. Probe and start run under a lock, so
    concurrent clients share one daemon.

    Before a start, ``settings`` (the ``daemon`` section) drive the self-healing: the log is rotated at
    ``logMaxBytes``, a start is refused after ``maxRestartsPerTenMinutes`` starts in ten minutes, each start in that
    window waits a jittered backoff, and after an out-of-memory exit the heap grows by ``heapGrowthFactor`` up to
    ``maxHeap`` (see :mod:`groovyscope.selfheal`)."""
    check = _probe(probe_fn)
    state = probe_state if state_fn is None else state_fn
    launch = start if start_fn is None else start_fn
    halt = stop_unlocked if stop_fn is None else stop_fn
    section: Mapping[str, Any] = settings or {}
    check_socket_path(socket_file)
    secure_dir(socket_file.parent)
    with start_lock(socket_file):
        status = check(socket_file)
        if status is None and state(socket_file) == PROBE_BUSY:
            LOG.info("daemon on %s is busy; not starting another", socket_file)
            return {"busy": True}
        if status is not None and expected_engine and status.get("engine") not in (None, expected_engine):
            LOG.info("daemon runs engine %s, expected %s; restarting it", status.get("engine"), expected_engine)
            halt(socket_file)
            forget_restarts(log_file)
            status = None
        if status is not None:
            return status
        command = prepare_start(list(command), log_file, section, sleep=sleep, now=now)
        return launch(command, socket_file, log_file, environ, probe_fn=check)


class CrashLoopRefusedError(RuntimeError):
    """The daemon keeps exiting as fast as it is started; no JVM is started, the reason says what to change."""


def direct_heap(command: list[str], log_file: Path, settings: Mapping[str, Any]) -> list[str]:
    """``command`` with the heap the self-healing grew for this workspace, for an engine started without the
    daemon: a heap that ran out of memory there would run out here too."""
    configured = selfheal.configured_heap(command, str(settings.get("heap", "1g")))
    grown = selfheal.kept_heap(selfheal.load_state(selfheal.state_path(log_file)), configured)
    return selfheal.with_heap(command, grown) if grown else command


def forget_restarts(log_file: Path) -> None:
    """Empties the crash-loop window after a deliberate stop, so asking for a start (as the refusal says to) or
    restarting after an upgrade is never refused as a crash loop."""
    state_file = selfheal.state_path(log_file)
    if state_file.exists():
        selfheal.save_state(state_file, selfheal.forgiven(selfheal.load_state(state_file)))


def prepare_start(
    command: list[str],
    log_file: Path,
    settings: Mapping[str, Any],
    *,
    sleep: Callable[[float], None] = time.sleep,
    now: Callable[[], float] = selfheal.clock,
) -> list[str]:
    """The self-healing step before a daemon start: judge the last exit from the log, refuse a crash loop, wait the
    backoff, rotate the log and grow the heap after an out-of-memory exit. Returns the command to run."""
    state_file = selfheal.state_path(log_file)
    previous = selfheal.load_state(state_file)
    cause = selfheal.exit_cause(log_file, previous)
    configured = selfheal.configured_heap(command, str(settings.get("heap", "1g")))
    moment = now()
    plan = selfheal.plan_restart(previous, cause, settings, configured, moment, physical=selfheal.physical_memory())
    if not plan["allowed"]:
        raise CrashLoopRefusedError(plan["reason"])
    if plan["reason"]:
        LOG.warning("%s", plan["reason"])
    if plan["delay"] > 0:
        LOG.info("waiting %.1fs before starting the daemon again", plan["delay"])
        sleep(plan["delay"])
    if selfheal.rotate_log(log_file, int(settings.get("logMaxBytes", 5_000_000)), int(settings.get("logsKept", 3))):
        LOG.info("rotated the daemon log %s", log_file)
    heap = plan["heap"] or configured
    if plan["heap"]:
        command = selfheal.with_heap(command, plan["heap"])
    log_size = log_file.stat().st_size if log_file.exists() else 0
    selfheal.save_state(state_file, selfheal.started(previous, heap, cause, log_size, moment, configured=configured))
    return command


def stop(
    socket_file: Path,
    *,
    probe_fn: Probe | None = None,
    notify: Callable[[Path, str], None] = rpc.notify,
    sleep: Callable[[float], None] = time.sleep,
    timeout: float = STOP_TIMEOUT_SECONDS,
) -> bool:
    """Ask a running daemon to exit and wait for its socket to go away. False when it was not running. Holds the
    start lock, so a start racing the stop never sees the old socket and keeps it."""
    with start_lock(socket_file):
        return stop_unlocked(socket_file, probe_fn=probe_fn, notify=notify, sleep=sleep, timeout=timeout)


def stop_unlocked(
    socket_file: Path,
    *,
    probe_fn: Probe | None = None,
    notify: Callable[[Path, str], None] = rpc.notify,
    sleep: Callable[[float], None] = time.sleep,
    timeout: float = STOP_TIMEOUT_SECONDS,
) -> bool:
    """``stop`` for a caller that already holds the start lock."""
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
    log_file = log_path(root, environ)
    saved = selfheal.load_state(selfheal.state_path(log_file))
    recent = [t for t in saved["restarts"] if 0 <= selfheal.clock() - t < selfheal.WINDOW_SECONDS]
    return {
        "socket": str(socket_file),
        "running": status is not None,
        "status": status,
        "log": str(log_file),
        "outOfMemory": status is None and out_of_memory(log_file),
        "heap": selfheal.grown_heap(saved),
        "restarts": len(recent),
    }


def out_of_memory(log_file: Path) -> bool:
    """Whether the daemon's log ends with an ``OutOfMemoryError``: the JVM exited for lack of heap."""
    return OOM_MARKER in log_tail(log_file)


def memory_use(status: Mapping[str, Any] | None) -> tuple[int, int] | None:
    """``(usedMb, maxMb)`` from a ``groovyscope/status`` answer, or nothing when the engine did not report it."""
    memory = (status or {}).get("memory")
    if not isinstance(memory, Mapping):
        return None
    try:
        used, maximum = int(memory["usedMb"]), int(memory["maxMb"])
    except (KeyError, TypeError, ValueError):
        return None
    return (used, maximum) if maximum > 0 else None


def memory_summary(status: Mapping[str, Any] | None) -> str | None:
    """``<used> of <max> MB (<share>%)`` for ``daemon status``."""
    use = memory_use(status)
    if use is None:
        return None
    used, maximum = use
    return f"{used} of {maximum} MB ({100 * used // maximum}%)"


def memory_warning(status: Mapping[str, Any] | None, ratio: float = HEAP_WARN_RATIO) -> str | None:
    """A warning once the heap in use passes ``ratio`` of the maximum, naming the fix; else nothing."""
    use = memory_use(status)
    if use is None or use[0] < ratio * use[1]:
        return None
    return f"heap {memory_summary(status)} in use; {HEAP_FIX} before the daemon runs out"


def out_of_memory_advice(log: str) -> str:
    """What to do when the last daemon died of an ``OutOfMemoryError``."""
    return f"the last daemon exited with OutOfMemoryError (log: {log}); {HEAP_FIX}"
