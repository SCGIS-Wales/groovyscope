"""`groovyscope classpath`: ask the project's own build tool for its compile and test classpath, once, on request.

Running Gradle or Maven executes the project's build scripts, so this is explicit only and refused in an untrusted
workspace. The result is a plain list of paths in ``.groovyscope/classpath.txt`` (``workspace.classpathFile``) that
the CLI hands to the engine as part of ``workspace.classpath``. Reading jars for name resolution runs no code from
them; AST transformations from the workspace classpath load only in a trusted workspace.
"""

from __future__ import annotations

import logging
import os
import shutil
import subprocess
import tempfile
from collections.abc import Callable, Mapping, Sequence
from importlib import resources
from pathlib import Path
from typing import TypedDict

LOG = logging.getLogger("groovyscope.classpath")
MARKER = "GROOVYSCOPE_CLASSPATH "
DEFAULT_FILE = ".groovyscope/classpath.txt"
DEFAULT_TIMEOUT_SECONDS = 900
GRADLE_FILES = ("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")
MAVEN_OUTPUT = "target/groovyscope-classpath.txt"

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


def write_file(target: Path, result: Export) -> None:
    """Write the list with a header saying how it was produced; atomically."""
    target.parent.mkdir(parents=True, exist_ok=True)
    command = [Path(result["command"][0]).name, *result["command"][1:]]
    header = [
        "# groovyscope classpath export: one path per line; regenerate with `groovyscope classpath`",
        f"# produced by: {' '.join(command)}",
    ]
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        handle.write("\n".join([*header, *result["paths"]]) + "\n")
        temporary = Path(handle.name)
    temporary.replace(target)


def read_file(path: Path) -> list[str]:
    """Existing paths listed in a classpath file; comments and blank lines are skipped."""
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return []
    entries = [line.strip() for line in lines if line.strip() and not line.lstrip().startswith("#")]
    return [entry for entry in entries if Path(entry).exists()]
