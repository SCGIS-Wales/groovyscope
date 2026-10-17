"""`groovyscope classpath`: ask the project's own build tool for its compile and test classpath, once, on request.

Running Gradle or Maven executes the project's build scripts, so this is explicit only and refused in an untrusted
workspace. The result is a plain list of paths in ``.groovyscope/classpath.txt`` (``workspace.classpathFile``) that
the CLI hands to the engine as part of ``workspace.classpath``. Reading jars for name resolution runs no code from
them; AST transformations from the workspace classpath load only in a trusted workspace.
"""

from __future__ import annotations

import hashlib
import logging
import os
import shutil
import subprocess
import tempfile
from collections.abc import Callable, Mapping, Sequence
from importlib import resources
from pathlib import Path
from typing import NotRequired, TypedDict

LOG = logging.getLogger("groovyscope.classpath")
MARKER = "GROOVYSCOPE_CLASSPATH "
DEFAULT_FILE = ".groovyscope/classpath.txt"
DEFAULT_TIMEOUT_SECONDS = 900
GRADLE_FILES = ("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")
MAVEN_OUTPUT = "target/groovyscope-classpath.txt"
# The files whose change means the exported classpath may be out of date: the root build files plus every
# nested module build file (Gradle subprojects, Maven modules, buildSrc) down to FINGERPRINT_DEPTH levels.
ROOT_BUILD_FILES = (*GRADLE_FILES, "pom.xml", "gradle.properties", "gradle/libs.versions.toml")
NESTED_BUILD_FILES = ("build.gradle", "build.gradle.kts", "pom.xml", "settings.gradle", "settings.gradle.kts")
SKIPPED_DIRS = frozenset(
    {".git", ".gradle", ".groovyscope", ".idea", ".kiro", "build", "node_modules", "out", "target"}
)
FINGERPRINT_DEPTH = 4
FINGERPRINT_LIMIT = 500
FINGERPRINT_MARKER = "# build-fingerprint: "
FIX = "groovyscope classpath"

Runner = Callable[[Sequence[str], Path, Mapping[str, str], int], tuple[int, str]]
Which = Callable[[str], str | None]


class Export(TypedDict):
    """What a classpath export found."""

    tool: str
    command: list[str]
    paths: list[str]
    missing: list[str]


def default_runner(command: Sequence[str], cwd: Path, environ: Mapping[str, str], timeout: int) -> tuple[int, str]:
    """Run the build tool; stdout and stderr together, a non-zero code on failure or timeout."""
    LOG.debug("running %s in %s", " ".join(command), cwd)
    try:
        done = subprocess.run(
            list(command), cwd=cwd, env=dict(environ), capture_output=True, text=True, timeout=timeout, check=False
        )
    except subprocess.TimeoutExpired:
        return (-1, f"{command[0]} did not finish within {timeout} s")
    except OSError as exc:
        return (-1, str(exc))
    return (done.returncode, done.stdout + "\n" + done.stderr)


def detect_tool(root: Path) -> str | None:
    """``gradle`` or ``maven`` from the files at the workspace root."""
    if any((root / name).is_file() for name in GRADLE_FILES):
        return "gradle"
    if (root / "pom.xml").is_file():
        return "maven"
    return None


def init_script() -> Path:
    """The bundled Gradle init script (a real file inside the installed package)."""
    return Path(str(resources.files("groovyscope").joinpath("_templates/config/gradle/groovyscope-classpath.gradle")))


def gradle_command(root: Path, which: Which) -> list[str] | None:
    wrapper = root / "gradlew"
    executable = str(wrapper) if wrapper.is_file() and os.access(wrapper, os.X_OK) else which("gradle")
    if executable is None:
        return None
    return [executable, "-q", "--no-configuration-cache", "--init-script", str(init_script()), "groovyscopeClasspath"]


def maven_command(root: Path, which: Which) -> list[str] | None:
    wrapper = root / "mvnw"
    executable = str(wrapper) if wrapper.is_file() and os.access(wrapper, os.X_OK) else which("mvn")
    if executable is None:
        return None
    return [
        executable,
        "-q",
        "-B",
        "dependency:build-classpath",
        "-Dmdep.includeScope=test",
        f"-Dmdep.outputFile={MAVEN_OUTPUT}",
    ]


def parse_gradle(output: str) -> list[str]:
    return [line[len(MARKER) :].strip() for line in output.splitlines() if line.startswith(MARKER)]


def parse_maven(root: Path) -> list[str]:
    """Every module's classpath file plus its compiled classes folders."""
    paths: list[str] = []
    for listing in sorted(root.rglob(MAVEN_OUTPUT)):
        module = listing.parent.parent
        paths += [str(module / "target" / "classes"), str(module / "target" / "test-classes")]
        paths += [p for p in listing.read_text(encoding="utf-8").strip().split(os.pathsep) if p]
        listing.unlink(missing_ok=True)
    return paths


def export(
    root: Path,
    environ: Mapping[str, str],
    *,
    tool: str | None = None,
    runner: Runner = default_runner,
    which: Which = shutil.which,
    timeout: int = DEFAULT_TIMEOUT_SECONDS,
) -> Export:
    """Run the build tool and collect the classpath. Raises ``ValueError`` with a reason on failure."""
    chosen = tool or detect_tool(root)
    if chosen is None:
        msg = f"no Gradle or Maven build at {root}; list jars under workspace.classpath instead"
        raise ValueError(msg)
    command = gradle_command(root, which) if chosen == "gradle" else maven_command(root, which)
    if command is None:
        msg = f"{chosen} is not installed and the project has no wrapper"
        raise ValueError(msg)
    code, output = runner(command, root, environ, timeout)
    if code != 0:
        tail = "\n".join(output.strip().splitlines()[-20:])
        msg = f"{' '.join(command[:1])} failed (exit {code}):\n{tail}"
        raise ValueError(msg)
    found = parse_gradle(output) if chosen == "gradle" else parse_maven(root)
    unique = list(dict.fromkeys(found))
    existing = [p for p in unique if Path(p).exists()]
    return {"tool": chosen, "command": command, "paths": existing, "missing": [p for p in unique if p not in existing]}


def build_files(root: Path) -> list[Path]:
    """The build files under ``root`` that decide its classpath, nearest first and in a stable order."""
    found = [root / name for name in ROOT_BUILD_FILES if (root / name).is_file()]
    pending: list[tuple[Path, int]] = [(root, 0)]
    while pending and len(found) < FINGERPRINT_LIMIT:
        folder, depth = pending.pop(0)
        try:
            entries = sorted(os.scandir(folder), key=lambda entry: entry.name)
        except OSError:
            continue
        for entry in entries:
            if entry.is_dir(follow_symlinks=False):
                if entry.name not in SKIPPED_DIRS and not entry.name.startswith(".") and depth < FINGERPRINT_DEPTH:
                    pending.append((Path(entry.path), depth + 1))
            elif depth > 0 and entry.name in NESTED_BUILD_FILES and entry.is_file(follow_symlinks=False):
                found.append(Path(entry.path))
    return found[:FINGERPRINT_LIMIT]


def build_fingerprint(root: Path) -> str:
    """A digest of every build file's path, size and modification time: the same value means the exported
    classpath still matches the build."""
    digest = hashlib.sha256()
    for file in build_files(root):
        try:
            stat = file.stat()
        except OSError:
            continue
        digest.update(f"{file.relative_to(root).as_posix()}|{stat.st_size}|{stat.st_mtime_ns}\n".encode())
    return digest.hexdigest()


def write_file(target: Path, result: Export, *, fingerprint: str | None = None) -> None:
    """Write the list with a header saying how it was produced and which build it matches; atomically."""
    target.parent.mkdir(parents=True, exist_ok=True)
    command = [Path(result["command"][0]).name, *result["command"][1:]]
    header = [
        "# groovyscope classpath export: one path per line; regenerate with `groovyscope classpath`",
        f"# produced by: {' '.join(command)}",
    ]
    if fingerprint:
        header.append(f"{FINGERPRINT_MARKER}{fingerprint}")
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        handle.write("\n".join([*header, *result["paths"]]) + "\n")
        temporary = Path(handle.name)
    try:
        if target.exists():
            shutil.copymode(target, temporary)
        else:
            mask = os.umask(0)
            os.umask(mask)
            temporary.chmod(0o666 & ~mask)
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def read_file(path: Path) -> list[str]:
    """Existing paths listed in a classpath file; comments and blank lines are skipped."""
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return []
    entries = [line.strip() for line in lines if line.strip() and not line.lstrip().startswith("#")]
    return [entry for entry in entries if Path(entry).exists()]


def count_entries(path: Path) -> int:
    """How many paths a classpath file lists (existing or not); 0 when it cannot be read."""
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return 0
    return sum(1 for line in lines if line.strip() and not line.lstrip().startswith("#"))


def read_fingerprint(path: Path) -> str | None:
    """The build fingerprint recorded in a classpath file, or None for a file written before fingerprints."""
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return None
    for line in lines:
        if line.startswith(FINGERPRINT_MARKER):
            return line[len(FINGERPRINT_MARKER) :].strip() or None
        if line.strip() and not line.startswith("#"):
            break
    return None


class Status(TypedDict):
    """Whether the engine's classpath still matches the build: ``fresh``, ``stale`` (a build file changed after
    the export), ``missing`` (a build exists but nothing was exported), ``configured`` (``workspace.classpath``
    lists the jars by hand) or ``none`` (no build tool, nothing to resolve)."""

    state: str
    entries: int
    message: str
    fix: NotRequired[str]


def status(root: Path, classpath_file: Path, *, configured: bool = False) -> Status:
    """The classpath state for ``root``; cheap enough to run on every request (stat calls only)."""
    tool = detect_tool(root)
    if classpath_file.is_file():
        entries = count_entries(classpath_file)
        recorded = read_fingerprint(classpath_file)
        if recorded is None:
            try:
                exported_at = classpath_file.stat().st_mtime
                changed = any(file.stat().st_mtime > exported_at for file in build_files(root))
            except OSError:
                changed = False
            reason = "a build file changed after the classpath was exported"
        else:
            changed = recorded != build_fingerprint(root)
            reason = "the build files changed after the classpath was exported"
        if changed:
            return {
                "state": "stale",
                "entries": entries,
                "message": f"{reason}; unresolved library classes may be new dependencies, not defects",
                "fix": FIX,
            }
        return {"state": "fresh", "entries": entries, "message": f"{entries} entries from {classpath_file.name}"}
    if configured:
        return {"state": "configured", "entries": 0, "message": "workspace.classpath set in the configuration"}
    if tool is not None:
        return {
            "state": "missing",
            "entries": 0,
            "message": f"no classpath yet ({tool} build found): library classes do not resolve and files with "
            "library imports get one GS100 warning each",
            "fix": FIX,
        }
    return {
        "state": "none",
        "entries": 0,
        "message": "no build tool here; list jars under workspace.classpath if needed",
    }
