"""Build the JVM command line that runs the engine. Safety flags are not configurable on purpose."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

from groovyscope import config as config_module
from groovyscope.java import java_executable

# Compile-time code execution paths in Groovy stay off regardless of configuration.
# An out-of-memory daemon exits instead of limping on with a full heap and wedged threads; the relay and the MCP
# server start a fresh one, and `daemon status`, `doctor` and `checkup` read the cause from the log.
OOM_FLAG = "-XX:+ExitOnOutOfMemoryError"
SAFETY_FLAGS: tuple[str, ...] = (
    "-Dgroovy.grape.enable=false",
    "-Dgroovy.asttest.enable=false",
    "-Dfile.encoding=UTF-8",
)


MIN_JDK_FOR_AUTO_CDS = 19


def build_engine_command(
    java_home: Path,
    jar: Path,
    config: Mapping[str, Any],
    engine_args: Sequence[str],
    *,
    cds_archive: Path | None = None,
    dump_dir: Path | None = None,
) -> list[str]:
    """``java <jvm flags> -jar <jar> <engine args>`` with the heap taken from ``daemon.heap``.

    The JVM exits on ``OutOfMemoryError`` (``OOM_FLAG``) rather than serving requests with a full heap. With
    ``daemon.heapDumpOnOutOfMemory`` and a ``dump_dir`` it writes a heap dump there first (opt in: a dump is the
    size of the heap).

    With ``cds_archive`` the JVM creates (first run) and then reuses a Class Data Sharing archive, which
    shortens daemon start-up. Pass it only for JDK 19 or newer.
    """
    daemon = config.get("daemon", {})
    heap = str(daemon.get("heap", config_module.default("daemon", "heap")))
    sharing = ["-Xshare:auto"]
    if cds_archive is not None:
        sharing = ["-XX:+AutoCreateSharedArchive", f"-XX:SharedArchiveFile={cds_archive}"]
    dumps: list[str] = []
    if dump_dir is not None and bool(
        daemon.get("heapDumpOnOutOfMemory", config_module.default("daemon", "heapDumpOnOutOfMemory"))
    ):
        dumps = ["-XX:+HeapDumpOnOutOfMemoryError", f"-XX:HeapDumpPath={dump_dir}"]
    return [
        str(java_executable(java_home)),
        f"-Xmx{heap}",
        "-XX:+UseSerialGC",
        OOM_FLAG,
        *dumps,
        *sharing,
        *SAFETY_FLAGS,
        "-jar",
        str(jar),
        *engine_args,
    ]
