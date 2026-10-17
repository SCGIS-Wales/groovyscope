"""JDK discovery: configured home, JAVA_HOME, macOS java_home, then PATH. Requires 17, recommends 21."""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import TypedDict

MINIMUM_JDK = 17
RECOMMENDED_JDK = 21
PROBE_TIMEOUT_SECONDS = 30

_VERSION_RE = re.compile(r'version "(?P<major>\d+)(?:\.(?P<minor>\d+))?')

Runner = Callable[[Sequence[str]], tuple[int, str]]
Which = Callable[[str], str | None]


class JdkInfo(TypedDict):
    """A JDK that answered ``java -version``."""

    home: str
    version: int
    source: str


def default_runner(command: Sequence[str]) -> tuple[int, str]:
    """Run a command and return ``(returncode, stdout + stderr)``; failures become a non-zero code."""
    try:
        completed = subprocess.run(
            list(command), capture_output=True, text=True, timeout=PROBE_TIMEOUT_SECONDS, check=False
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return (-1, str(exc))
    return (completed.returncode, completed.stdout + completed.stderr)


def parse_major_version(output: str) -> int | None:
    """``openjdk version "21.0.1"`` -> 21; legacy ``"1.8.0_392"`` -> 8; unparseable -> None."""
    match = _VERSION_RE.search(output)
    if match is None:
        return None
    major = int(match.group("major"))
    minor = match.group("minor")
    if major == 1 and minor is not None:
        return int(minor)
    return major


def java_executable(home: Path) -> Path:
    return home / "bin" / "java"


def candidate_homes(
    configured: str | None,
    environ: Mapping[str, str],
    *,
    platform: str,
    which: Which,
    runner: Runner,
    provisioned: Sequence[Path] = (),
) -> list[tuple[str, Path]]:
    """Possible JDK homes in priority order, each tagged with where it came from.

    ``provisioned`` are JDKs groovyscope unpacked itself (``groovyscope jdk install``); they come last so an
    installed JDK keeps winning.
    """
    candidates: list[tuple[str, Path]] = []
    if configured:
        candidates.append(("config jdk.home", Path(configured).expanduser()))
    java_home = environ.get("JAVA_HOME", "")
    if java_home:
        candidates.append(("JAVA_HOME", Path(java_home)))
    if platform == "darwin":
        code, output = runner(["/usr/libexec/java_home", "-v", f"{MINIMUM_JDK}+"])
        first_line = output.strip().splitlines()[0] if output.strip() else ""
        if code == 0 and first_line:
            candidates.append(("/usr/libexec/java_home", Path(first_line)))
    on_path = which("java")
    if on_path:
        candidates.append(("PATH", Path(on_path).resolve().parent.parent))
    candidates.extend(("provisioned", home) for home in provisioned)
    return candidates


def probe_version(home: Path, runner: Runner) -> int | None:
    """Major version reported by ``<home>/bin/java -version``, or None when it is not a runnable JDK."""
    executable = java_executable(home)
    if not executable.is_file():
        return None
    code, output = runner([str(executable), "-version"])
    if code != 0:
        return None
    return parse_major_version(output)


def discover_jdk(
    configured: str | None = None,
    environ: Mapping[str, str] | None = None,
    *,
    platform: str = sys.platform,
    which: Which = shutil.which,
    runner: Runner = default_runner,
    minimum: int = MINIMUM_JDK,
    provisioned: Sequence[Path] = (),
) -> JdkInfo | None:
    """First candidate meeting ``minimum``; otherwise the newest JDK found (so callers can explain); else None."""
    env = os.environ if environ is None else environ
    seen: set[Path] = set()
    best: JdkInfo | None = None
    homes = candidate_homes(configured, env, platform=platform, which=which, runner=runner, provisioned=provisioned)
    for source, home in homes:
        resolved = home.resolve() if home.exists() else home
        if resolved in seen:
            continue
        seen.add(resolved)
        version = probe_version(resolved, runner)
        if version is None:
            continue
        info: JdkInfo = {"home": str(resolved), "version": version, "source": source}
        if version >= minimum:
            return info
        if best is None or version > best["version"]:
            best = info
    return best


def jdk_status(info: JdkInfo | None, minimum: int = MINIMUM_JDK, recommended: int = RECOMMENDED_JDK) -> tuple[str, str]:
    """``(level, message)`` where level is ``ok``, ``warn`` or ``fail``."""
    if info is None:
        return (
            "fail",
            f"no JDK found; install JDK {recommended} (see docs/install.md) or run `groovyscope jdk install`",
        )
    if info["version"] < minimum:
        return ("fail", f"JDK {info['version']} at {info['home']} is below the minimum {minimum}")
    if info["version"] < recommended:
        return ("warn", f"JDK {info['version']} at {info['home']} works; {recommended} is recommended")
    return ("ok", f"JDK {info['version']} at {info['home']} (from {info['source']})")
