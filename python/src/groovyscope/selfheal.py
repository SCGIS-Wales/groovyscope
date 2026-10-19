"""Self-healing of the engine daemon after an exit: a restart budget with backoff, and a heap that grows once after
an out-of-memory exit, up to a ceiling the user set.

The daemon JVM exits on ``OutOfMemoryError`` (``launch.OOM_FLAG``). Left alone, the front end would start a JVM
with the same heap on the next request, which loads the same workspace and dies the same way: a crash loop that
costs a JVM start per request and tells the user nothing. This module keeps a small state file next to the
daemon log (last heap, recent start times, the log size at the last start) and decides, before each start, whether
to start at all, how long to wait first, and with which heap. Everything here is pure except the state file and
the log tail; ``daemon.ensure_running`` wires it in.
"""

from __future__ import annotations

import json
import os
import random
import re
import time
from collections.abc import Callable, Mapping
from pathlib import Path
from typing import Any, TypedDict

from groovyscope import atomic

STATE_SUFFIX = ".state.json"
WINDOW_SECONDS = 600.0  # the restart budget's window
BACKOFF_BASE_SECONDS = 1.0
BACKOFF_CAP_SECONDS = 30.0
MAX_BACKOFF_SECONDS = BACKOFF_CAP_SECONDS * 1.5  # the cap times the largest jitter factor (0.5 + jitter)
TAIL_BYTES = 65536  # how much of the log after the last start is searched for the exit cause
OOM_MARKER = "OutOfMemoryError"
# Only these mean the heap was too small: a bigger -Xmx helps. Metaspace, native threads, direct buffers and the
# rest are OutOfMemoryErrors that a bigger heap does not cure (it can make some of them worse).
HEAP_EXHAUSTED = (
    "java.lang.OutOfMemoryError: Java heap space",
    "java.lang.OutOfMemoryError: GC overhead limit exceeded",
)
OOM_KIND = re.compile(r"java\.lang\.OutOfMemoryError: ([^\r\n]+)")
OTHER_OOM_ADVICE = {
    "Metaspace": "too many classes were loaded (Metaspace); a bigger heap does not help: trim workspace.classpath "
    "or restart the daemon",
    "unable to create native thread": "the system refused another thread (check `ulimit -u` and other JVMs); a "
    "bigger heap does not help",
    "Direct buffer memory": "the JVM ran out of direct (native) memory; a bigger heap does not help: restart the "
    "daemon, and report it with `groovyscope doctor --bundle` if it recurs",
}
AUTO = "auto"
GIB = 1024**3
MIB = 1024**2
DEFAULT_MAX_HEAP_BYTES = 4 * GIB
PHYSICAL_SHARE = 4  # `maxHeap: auto` is a quarter of physical memory, capped at DEFAULT_MAX_HEAP_BYTES
UNITS = {"k": 1024, "m": MIB, "g": GIB}
EXIT_OOM = "oom"  # the heap ran out: the next start grows it
EXIT_OOM_OTHER = "oom-other"  # another OutOfMemoryError (Metaspace, threads, direct memory): the heap stays
EXIT_OTHER = "exit"
EXIT_NONE = "none"  # the log did not grow: the previous JVM wrote nothing (or this is the first start)


class DaemonState(TypedDict):
    """What is remembered between daemon starts."""

    heap: str | None  # the heap the last start used (``None`` before the first start)
    configured: str | None  # ``daemon.heap`` as configured at that start, to tell a grown heap apart
    restarts: list[float]  # start times (epoch seconds) inside the window
    lastExit: str  # EXIT_OOM | EXIT_OTHER | EXIT_NONE, as judged before the last start
    logSize: int  # the daemon log's size when the last JVM was started


class RestartPlan(TypedDict):
    """The decision before a start."""

    allowed: bool
    delay: float  # seconds to wait before starting (jittered backoff), 0 for the first start in the window
    heap: str | None  # the heap to start with when it differs from the command's ``-Xmx``
    cause: str  # what ended the previous JVM
    reason: str  # why a start is refused, or a note on what changed


def empty_state() -> DaemonState:
    return {"heap": None, "configured": None, "restarts": [], "lastExit": EXIT_NONE, "logSize": 0}


def state_path(log_file: Path) -> Path:
    """The state file lives next to the daemon log: ``<key>.state.json``."""
    return log_file.with_name(log_file.stem + STATE_SUFFIX)


def load_state(path: Path) -> DaemonState:
    """The saved state, or an empty one when the file is missing or unreadable."""
    try:
        loaded = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return empty_state()
    if not isinstance(loaded, dict):
        return empty_state()
    state = empty_state()
    heap = loaded.get("heap")
    state["heap"] = heap if isinstance(heap, str) and heap else None
    configured = loaded.get("configured")
    state["configured"] = configured if isinstance(configured, str) and configured else None
    restarts = loaded.get("restarts")
    if isinstance(restarts, list):
        state["restarts"] = [float(t) for t in restarts if isinstance(t, (int, float))]
    last = loaded.get("lastExit")
    state["lastExit"] = last if last in {EXIT_OOM, EXIT_OOM_OTHER, EXIT_OTHER, EXIT_NONE} else EXIT_NONE
    size = loaded.get("logSize")
    state["logSize"] = int(size) if isinstance(size, (int, float)) and size >= 0 else 0
    return state


def save_state(path: Path, state: DaemonState) -> None:
    atomic.write_text(path, json.dumps(state, indent=2) + "\n")


def parse_heap(text: str) -> int:
    """``1g``, ``1536m`` or ``2048k`` as bytes."""
    value = text.strip().lower()
    if not value or value[-1] not in UNITS or not value[:-1].isdigit():
        msg = f"heap size {text!r} is not <number>k|m|g"
        raise ValueError(msg)
    return int(value[:-1]) * UNITS[value[-1]]


def format_heap(size: int) -> str:
    """Bytes as the JVM spelling, in whole gigabytes when exact, else whole megabytes."""
    if size % GIB == 0:
        return f"{size // GIB}g"
    return f"{max(1, size // MIB)}m"


def physical_memory() -> int | None:
    """The machine's physical memory in bytes, when the platform tells."""
    try:
        pages = os.sysconf("SC_PHYS_PAGES")
        page = os.sysconf("SC_PAGE_SIZE")
    except (ValueError, OSError, AttributeError):
        return None
    return pages * page if pages > 0 and page > 0 else None


def max_heap_bytes(setting: str, physical: int | None) -> int:
    """``daemon.maxHeap``: ``auto`` is a quarter of physical memory capped at 4g (4g when unknown), else the size."""
    if str(setting).strip().lower() == AUTO:
        return min(DEFAULT_MAX_HEAP_BYTES, physical // PHYSICAL_SHARE) if physical else DEFAULT_MAX_HEAP_BYTES
    return parse_heap(str(setting))


def exit_cause(log_file: Path, state: DaemonState) -> str:
    """What ended the last JVM, read from the part of the log it wrote: ``oom`` when it mentions an
    ``OutOfMemoryError``, ``exit`` for anything else, ``none`` when the log did not grow."""
    try:
        size = log_file.stat().st_size
    except OSError:
        return EXIT_NONE
    if size <= state["logSize"]:
        return EXIT_NONE
    start = max(state["logSize"], size - TAIL_BYTES)
    try:
        with log_file.open("rb") as handle:
            handle.seek(start)
            written = handle.read(size - start).decode("utf-8", errors="replace")
    except OSError:
        return EXIT_NONE
    return exit_cause_of(written)


def exit_cause_of(text: str) -> str:
    """``oom`` when the JVM ran out of heap, ``oom-other`` for any other OutOfMemoryError, ``exit`` otherwise."""
    if any(marker in text for marker in HEAP_EXHAUSTED):
        return EXIT_OOM
    return EXIT_OOM_OTHER if OOM_MARKER in text else EXIT_OTHER


def oom_advice(text: str) -> str | None:
    """What to do about the last non-heap OutOfMemoryError in ``text``; None for a heap one or none at all."""
    if exit_cause_of(text) != EXIT_OOM_OTHER:
        return None
    kinds = OOM_KIND.findall(text)
    kind = kinds[-1].strip() if kinds else "unknown kind"
    for name, advice in OTHER_OOM_ADVICE.items():
        if name in kind:
            return advice
    return f"the JVM ran out of memory that is not heap ({kind}); a bigger heap does not help: restart the daemon"


def kept_heap(state: DaemonState, configured_heap: str) -> str | None:
    """The heap grown earlier that the next start keeps: only while ``daemon.heap`` is still the value it grew from
    and only when it is larger (the user changed or raised ``daemon.heap`` since: theirs stands)."""
    previous = state["heap"]
    if not previous:
        return None
    if state["configured"] is not None and state["configured"] != configured_heap:
        return None
    return previous if parse_heap(previous) > parse_heap(configured_heap) else None


def plan_restart(
    state: DaemonState,
    cause: str,
    settings: Mapping[str, Any],
    configured_heap: str,
    now: float,
    *,
    physical: int | None = None,
    jitter: Callable[[], float] = random.random,
) -> RestartPlan:
    """Whether to start, after how long, and with which heap.

    A start is refused once ``daemon.maxRestartsPerTenMinutes`` starts happened inside the window: the daemon is
    dying as fast as it is started and another JVM would only die too. Each start inside the window waits a
    jittered exponential backoff. After an out-of-memory exit the heap grows by ``daemon.heapGrowthFactor`` up
    to ``daemon.maxHeap``; a heap grown earlier is kept while ``daemon.heap`` is still the one it grew from and
    only when it is larger than ``daemon.heap``."""
    recent = [t for t in state["restarts"] if 0 <= now - t < WINDOW_SECONDS]
    budget = max(1, int(settings.get("maxRestartsPerTenMinutes", 3)))
    previous = kept_heap(state, configured_heap)
    current = previous or configured_heap
    if len(recent) >= budget:
        reason = (
            f"the daemon was started {len(recent)} times in the last ten minutes and keeps exiting (last exit: "
            f"{cause}); not starting it again for now. Raise `daemon.heap` (now {current}) or `daemon.maxHeap` in "
            "`.groovyscope.yaml` and read the daemon log, then `groovyscope daemon start`"
        )
        return {"allowed": False, "delay": 0.0, "heap": None, "cause": cause, "reason": reason}
    delay = 0.0
    if recent:
        delay = min(BACKOFF_CAP_SECONDS, BACKOFF_BASE_SECONDS * 2 ** (len(recent) - 1)) * (0.5 + jitter())
    heap: str | None = previous
    reason = ""
    if cause == EXIT_OOM:
        factor = max(1.0, float(settings.get("heapGrowthFactor", 1.5)))
        ceiling = max_heap_bytes(str(settings.get("maxHeap", AUTO)), physical)
        now_bytes = parse_heap(current)
        grown = min(ceiling, int(now_bytes * factor))
        if grown > now_bytes:
            heap = format_heap(grown)
            reason = f"the last daemon exited with OutOfMemoryError on a {current} heap; starting with {heap}"
        else:
            reason = (
                f"the last daemon exited with OutOfMemoryError and the heap is already at daemon.maxHeap ({current}); "
                "raise `daemon.maxHeap` in `.groovyscope.yaml` or narrow the workspace"
            )
    elif cause == EXIT_OOM_OTHER:
        reason = "the last daemon exited with an OutOfMemoryError that a bigger heap does not cure; the heap stays"
    return {"allowed": True, "delay": delay, "heap": heap, "cause": cause, "reason": reason}


def with_heap(command: list[str], heap: str) -> list[str]:
    """The engine command with its ``-Xmx`` replaced."""
    return [f"-Xmx{heap}" if arg.startswith("-Xmx") else arg for arg in command]


def configured_heap(command: list[str], fallback: str = "1g") -> str:
    """The ``-Xmx`` value a command carries."""
    for arg in command:
        if arg.startswith("-Xmx"):
            return arg[len("-Xmx") :]
    return fallback


def started(
    state: DaemonState, heap: str, cause: str, log_size: int, now: float, *, configured: str | None = None
) -> DaemonState:
    """The state after a start: this start joins the window, the heap and the log size are recorded."""
    recent = [t for t in state["restarts"] if 0 <= now - t < WINDOW_SECONDS]
    return {
        "heap": heap,
        "configured": configured or state["configured"],
        "restarts": [*recent, now],
        "lastExit": cause,
        "logSize": log_size,
    }


def forgiven(state: DaemonState) -> DaemonState:
    """The state after a stop someone asked for (``daemon stop``, ``restart`` or ``start``, or the swap to a newer
    engine): the starts before it were not a crash loop, so the window starts empty. The grown heap is kept."""
    return {
        "heap": state["heap"],
        "configured": state["configured"],
        "restarts": [],
        "lastExit": state["lastExit"],
        "logSize": state["logSize"],
    }


def grown_heap(state: DaemonState) -> str | None:
    """The heap the self-healing grew past ``daemon.heap``, or nothing when the configured one is in use."""
    if state["heap"] and state["configured"] and state["heap"] != state["configured"]:
        return state["heap"]
    return None


def rotate_log(log_file: Path, max_bytes: int, kept: int) -> bool:
    """Rotates the daemon log (``.1`` newest, ``.<kept>`` oldest) when it is over ``max_bytes``; True when rotated."""
    try:
        if max_bytes <= 0 or log_file.stat().st_size <= max_bytes:
            return False
    except OSError:
        return False
    kept = max(1, kept)
    for index in range(kept, 0, -1):
        older = log_file.with_name(f"{log_file.name}.{index}")
        if index == kept:
            older.unlink(missing_ok=True)
            continue
        if older.exists():
            os.replace(older, log_file.with_name(f"{log_file.name}.{index + 1}"))  # noqa: PTH105
    os.replace(log_file, log_file.with_name(f"{log_file.name}.1"))  # noqa: PTH105
    return True


def clock() -> float:
    return time.time()
