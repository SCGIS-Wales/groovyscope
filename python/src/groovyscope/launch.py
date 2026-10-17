"""Build the JVM command line that runs the engine. Safety flags are not configurable on purpose."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

from groovyscope.java import java_executable

# Compile-time code execution paths in Groovy stay off regardless of configuration.
SAFETY_FLAGS: tuple[str, ...] = (
    "-Dgroovy.grape.enable=false",
    "-Dgroovy.asttest.enable=false",
    "-Dfile.encoding=UTF-8",
)


def build_engine_command(
    java_home: Path, jar: Path, config: Mapping[str, Any], engine_args: Sequence[str]
) -> list[str]:
    """``java <jvm flags> -jar <jar> <engine args>`` with the heap taken from ``daemon.heap``."""
    daemon = config.get("daemon", {})
    heap = str(daemon.get("heap", "1g"))
    return [
        str(java_executable(java_home)),
        f"-Xmx{heap}",
        "-XX:+UseSerialGC",
        "-Xshare:auto",
        *SAFETY_FLAGS,
        "-jar",
        str(jar),
        *engine_args,
    ]
