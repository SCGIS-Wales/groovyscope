"""JDK discovery: configured home, JAVA_HOME, macOS java_home, PATH, standard JVM folders, then JDKs groovyscope
provisioned. Requires 17 (``jdk.minimum``), recommends 21.

A JDK's version and vendor come from its ``release`` file when it has one (every modern JDK, Amazon Corretto
included), so discovery does not have to start a JVM; ``java -version`` is the fallback.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import NotRequired, TypedDict

MINIMUM_JDK = 17
RECOMMENDED_JDK = 21
PROBE_TIMEOUT_SECONDS = 30

_VERSION_RE = re.compile(r'version "(?P<major>\d+)(?:\.(?P<minor>\d+))?')
_RELEASE_LINE = re.compile(r'^(?P<key>[A-Z_]+)="?(?P<value>.*?)"?\s*$')
_JAVA_HOME_PROPERTY = re.compile(r"^\s*java\.home\s*=\s*(?P<home>.+?)\s*$", re.MULTILINE)
# Folders where JDKs are conventionally installed, searched after the explicit sources.
STANDARD_JVM_DIRS: dict[str, tuple[str, ...]] = {
    "darwin": ("~/Library/Java/JavaVirtualMachines", "/Library/Java/JavaVirtualMachines"),
    "linux": ("/usr/lib/jvm", "/usr/java", "/opt/java"),
}

Runner = Callable[[Sequence[str]], tuple[int, str]]
Which = Callable[[str], str | None]


class JdkInfo(TypedDict):
    """A usable JDK: its home, major version and where it was found."""

    home: str
    version: int
    source: str
    vendor: NotRequired[str]
    full_version: NotRequired[str]


class Discovery(TypedDict):
    """The chosen JDK (or the best one found below the minimum) plus why other candidates were passed over."""

    jdk: JdkInfo | None
    notes: list[str]


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
    return major_of(match.group("major"), match.group("minor"))


def major_of(major_text: str, minor_text: str | None) -> int:
    major = int(major_text)
    if major == 1 and minor_text is not None:
        return int(minor_text)
    return major


def parse_release(text: str) -> dict[str, str]:
    """``KEY="value"`` lines of a JDK ``release`` file."""
    values: dict[str, str] = {}
    for line in text.splitlines():
        match = _RELEASE_LINE.match(line)
        if match:
            values[match.group("key")] = match.group("value")
    return values


def release_version(home: Path) -> tuple[int, str, str] | None:
    """``(major, full version, vendor)`` from ``<home>/release``; None when absent or unreadable."""
    try:
        values = parse_release((home / "release").read_text(encoding="utf-8", errors="replace"))
    except OSError:
        return None
    full = values.get("JAVA_VERSION", "")
    match = re.match(r"(?P<major>\d+)(?:\.(?P<minor>\d+))?", full)
    if match is None:
        return None
    vendor = values.get("IMPLEMENTOR_VERSION") or values.get("IMPLEMENTOR") or ""
    return (major_of(match.group("major"), match.group("minor")), full, vendor)


def java_executable(home: Path) -> Path:
    return home / "bin" / "java"


def normalize_home(path: Path) -> Path:
    """A JDK home from what a user is likely to point at: the home itself, a macOS ``.jdk`` bundle (its
    ``Contents/Home``), or the ``bin`` directory or ``java`` binary inside a home."""
    if java_executable(path).is_file():
        return path
    bundle = path / "Contents" / "Home"
    if java_executable(bundle).is_file():
        return bundle
    if path.name == "java" and path.parent.name == "bin":
        return path.parent.parent
    if path.name == "bin" and (path / "java").is_file():
        return path.parent
    return path


def path_java_home(which: Which, runner: Runner) -> Path | None:
    """The home behind ``java`` on PATH. Version managers (asdf, jenv, mise, SDKMAN shims) put a script there,
    so when the binary's grandparent is not a JDK, ask the JVM for ``java.home``."""
    on_path = which("java")
    if not on_path:
        return None
    guess = Path(on_path).resolve().parent.parent
    if java_executable(guess).is_file():
        return guess
    code, output = runner([on_path, "-XshowSettings:properties", "-version"])
    match = _JAVA_HOME_PROPERTY.search(output) if code == 0 else None
    return Path(match.group("home")) if match else None


def standard_homes(platform: str, home_dir: Path | None = None) -> list[Path]:
    """JDK homes in the conventional install folders, newest-looking names first."""
    found: list[Path] = []
    for folder in STANDARD_JVM_DIRS.get(platform, ()):
        base = Path(folder.replace("~", str(home_dir or Path.home()), 1)) if folder.startswith("~") else Path(folder)
        try:
            entries = sorted(base.iterdir(), key=lambda p: p.name, reverse=True)
        except OSError:
            continue
        found.extend(normalize_home(entry) for entry in entries if entry.is_dir())
    return found


def candidate_homes(
    configured: str | None,
    environ: Mapping[str, str],
    *,
    platform: str,
    which: Which,
    runner: Runner,
    provisioned: Sequence[Path] = (),
    minimum: int = MINIMUM_JDK,
    scan: bool = True,
) -> list[tuple[str, Path]]:
    """Possible JDK homes in priority order, each tagged with where it came from.

    ``provisioned`` are JDKs groovyscope unpacked itself (``groovyscope jdk install``); they come last so an
    installed JDK keeps winning.
    """
    candidates: list[tuple[str, Path]] = []
    if configured:
        candidates.append(("config jdk.home", normalize_home(Path(configured).expanduser())))
    java_home = environ.get("JAVA_HOME", "")
    if java_home:
        candidates.append(("JAVA_HOME", normalize_home(Path(java_home).expanduser())))
    if platform == "darwin":
        code, output = runner(["/usr/libexec/java_home", "-v", f"{minimum}+"])
        first_line = output.strip().splitlines()[0] if output.strip() else ""
        if code == 0 and first_line:
            candidates.append(("/usr/libexec/java_home", Path(first_line)))
    on_path = path_java_home(which, runner)
    if on_path is not None:
        candidates.append(("PATH", on_path))
    if scan:
        candidates.extend(("installed JDKs", home) for home in standard_homes(platform))
    candidates.extend(("provisioned", home) for home in provisioned)
    return candidates


def probe(home: Path, runner: Runner) -> tuple[int, str, str] | None:
    """``(major, full version, vendor)`` of a runnable JDK home, from ``release`` or ``java -version``."""
    executable = java_executable(home)
    if not executable.is_file() or not os.access(executable, os.X_OK):
        return None
    from_release = release_version(home)
    if from_release is not None:
        return from_release
    code, output = runner([str(executable), "-version"])
    if code != 0:
        return None
    major = parse_major_version(output)
    if major is None:
        return None
    full = re.search(r'version "([^"]+)"', output)
    return (major, full.group(1) if full else str(major), "")


def probe_version(home: Path, runner: Runner) -> int | None:
    """Major version of the JDK at ``home``, or None when it is not a runnable JDK."""
    found = probe(home, runner)
    return found[0] if found else None


def discover(
    configured: str | None = None,
    environ: Mapping[str, str] | None = None,
    *,
    platform: str = sys.platform,
    which: Which = shutil.which,
    runner: Runner = default_runner,
    minimum: int = MINIMUM_JDK,
    provisioned: Sequence[Path] = (),
    scan: bool = True,
) -> Discovery:
    """First candidate meeting ``minimum``; otherwise the newest JDK found (so callers can explain). Explicit
    sources (``jdk.home``, ``JAVA_HOME``) that are unusable or too old are reported in ``notes``."""
    env = os.environ if environ is None else environ
    seen: set[Path] = set()
    best: JdkInfo | None = None
    notes: list[str] = []
    homes = candidate_homes(
        configured,
        env,
        platform=platform,
        which=which,
        runner=runner,
        provisioned=provisioned,
        minimum=minimum,
        scan=scan,
    )
    for source, home in homes:
        resolved = home.resolve() if home.exists() else home
        if resolved in seen:
            continue
        seen.add(resolved)
        found = probe(resolved, runner)
        explicit = source in {"config jdk.home", "JAVA_HOME"}
        if found is None:
            if explicit:
                notes.append(f"{source} ({home}) is not a JDK home (no runnable bin/java); ignored")
            continue
        major, full, vendor = found
        info: JdkInfo = {"home": str(resolved), "version": major, "source": source, "full_version": full}
        if vendor:
            info["vendor"] = vendor
        if major >= minimum:
            return {"jdk": info, "notes": notes}
        if explicit:
            notes.append(f"{source} ({resolved}) is JDK {major}, below the minimum {minimum}; ignored")
        if best is None or major > best["version"]:
            best = info
    return {"jdk": best, "notes": notes}


def discover_jdk(
    configured: str | None = None,
    environ: Mapping[str, str] | None = None,
    *,
    platform: str = sys.platform,
    which: Which = shutil.which,
    runner: Runner = default_runner,
    minimum: int = MINIMUM_JDK,
    provisioned: Sequence[Path] = (),
    scan: bool = True,
) -> JdkInfo | None:
    """The JDK :func:`discover` chose."""
    return discover(
        configured,
        environ,
        platform=platform,
        which=which,
        runner=runner,
        minimum=minimum,
        provisioned=provisioned,
        scan=scan,
    )["jdk"]


def describe(info: JdkInfo) -> str:
    """``JDK 21 (Corretto-21.0.12.12.1, 21.0.12.1)`` style label."""
    details = [part for part in (info.get("vendor", ""), info.get("full_version", "")) if part]
    return f"JDK {info['version']}" + (f" ({', '.join(details)})" if details else "")


def jdk_status(info: JdkInfo | None, minimum: int = MINIMUM_JDK, recommended: int = RECOMMENDED_JDK) -> tuple[str, str]:
    """``(level, message)`` where level is ``ok``, ``warn`` or ``fail``."""
    if info is None:
        return (
            "fail",
            f"no JDK found; install JDK {recommended} (see docs/install.md) or run `groovyscope jdk install`",
        )
    if info["version"] < minimum:
        return ("fail", f"{describe(info)} at {info['home']} is below the minimum {minimum}")
    if info["version"] < recommended:
        return ("warn", f"{describe(info)} at {info['home']} works; {recommended} is recommended")
    return ("ok", f"{describe(info)} at {info['home']} (from {info['source']})")
