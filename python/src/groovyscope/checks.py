"""The owner's Kiro checks (docs/kiro-checks.md) as commands: a throwaway workspace ready for every check, and a
report to paste back.

``prepare`` copies the bundled CodeNarc corpus, adds a Gradle build file and a Jenkinsfile, and makes it a git
repository with a ``main`` branch and a ``change`` branch that carries one reviewable change, so no step of the
checks depends on pasting a block of shell. Everything here is pure or takes its collaborators (``runner``,
``clock``) as arguments.
"""

from __future__ import annotations

import shutil
import subprocess
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path

# Written into the workspace so `prepare --force` only ever replaces a workspace it made itself.
MARKER = ".groovyscope-checks"
DEFAULT_DIRECTORY = "gs-check"
BRANCH_BASE = "main"
BRANCH_CHANGE = "change"
CHANGED_FILE = "src/main/groovy/org/codenarc/report/JsonReportWriter.groovy"
# Git identity for the throwaway commits only (passed with -c, never written to the user's configuration).
GIT_IDENTITY = ("-c", "user.name=groovyscope checks", "-c", "user.email=checks@groovyscope.invalid")

JENKINSFILE = """pipeline {
    agent any
    stages {
        stage('build') {
            steps {
                echo 'hi'
            }
        }
    }
}
"""
BUILD_GRADLE = """plugins {
    id 'groovy'
}

repositories {
    mavenCentral()
}
"""
# One reviewable change for the merge request review check: a catch that swallows every Exception.
CHANGE_METHOD = """
    protected String describe(Object value) {
        try {
            return value.toString()
        } catch (Exception e) {
        }
        return ''
    }
"""

Runner = Callable[[Sequence[str], Path], None]


def default_runner(command: Sequence[str], cwd: Path) -> None:
    subprocess.run(list(command), cwd=cwd, check=True, capture_output=True, text=True)


def is_checks_workspace(target: Path) -> bool:
    return (target / MARKER).is_file()


def with_change(text: str) -> str:
    """The source with :data:`CHANGE_METHOD` added as the last member of its class."""
    end = text.rstrip().rfind("}")
    return text[:end].rstrip() + "\n" + CHANGE_METHOD + "}\n"


def prepare(target: Path, corpus: Path, runner: Runner = default_runner) -> list[str]:
    """Builds the workspace in ``target`` (which must not exist) from the unpacked ``corpus``: the sources, a
    ``build.gradle``, a ``Jenkinsfile``, git history on ``main`` and the ``change`` branch checked out. The lines
    say what was done."""
    shutil.copytree(corpus, target)
    (target / MARKER).write_text("made by `groovyscope checks prepare`; safe to delete\n", encoding="utf-8")
    (target / "build.gradle").write_text(BUILD_GRADLE, encoding="utf-8")
    (target / "Jenkinsfile").write_text(JENKINSFILE, encoding="utf-8")
    git = ["git", *GIT_IDENTITY]
    runner(["git", "init", "-q", "-b", BRANCH_BASE], target)
    # Kiro's files and the marker stay out of `git status`, which the checks read to see what a tool wrote.
    exclude = target / ".git" / "info" / "exclude"
    exclude.parent.mkdir(parents=True, exist_ok=True)
    with exclude.open("a", encoding="utf-8") as handle:
        handle.write(f"\n.kiro/\n{MARKER}\n")
    runner(["git", "add", "-A"], target)
    runner([*git, "commit", "-q", "-m", "base"], target)
    runner(["git", "checkout", "-q", "-b", BRANCH_CHANGE], target)
    changed = target / CHANGED_FILE
    changed.write_text(with_change(changed.read_text(encoding="utf-8")), encoding="utf-8")
    runner([*git, "commit", "-q", "-am", "change: describe() swallows every exception"], target)
    return [
        f"copied the CodeNarc corpus to {target}",
        "added build.gradle and Jenkinsfile",
        f"git: '{BRANCH_BASE}' holds the corpus; '{BRANCH_CHANGE}' (checked out) adds a catch to {CHANGED_FILE}",
    ]


def next_steps(target: Path) -> list[str]:
    return [
        "next, in a terminal:",
        f"  cd {target}",
        "  kiro-cli chat --agent groovy",
        "then follow docs/kiro-checks.md from section A; at the end run `groovyscope checks report` there",
    ]


REPORT_ROWS = (
    "A start line, /code status, /mcp, /tools, /context show, /prompts",
    "B code tool on .groovy, build.gradle and Jenkinsfile",
    "C six navigation prompts called the right tools (any grep or cat?)",
    "D fix: Kiro approval, then a groovyscope question? no wrote nothing, yes wrote",
    "D fix answer: fixed and left counts; the import came with the type",
    "D format and rename asked; rename verified",
    "E after-write block in the same turn; stop gate refused the turn",
    "F /groovy-impact (terminal UI) or @groovy-impact (classic) ran",
    "G review of the change branch: verdict, the swallowed catch, draft comments",
    "H classpath on your own project: stale, refresh with question, fresh, lock message",
    "I diagram from a folder: no line over a box; GS001 practices resource",
    "J parallel prompt; memory drill outcome",
    "K disabledTools honoured and kept by repair",
    "L V3 engine: start line, read-only ran, fix asked",
)


def report(facts: Mapping[str, str], doctor_rows: Sequence[tuple[str, str, str]]) -> list[str]:
    """The block to paste back: versions and environment facts, every doctor line, and the result table with
    empty cells to fill in."""
    lines = ["## groovyscope Kiro check report", ""]
    lines += [f"- {name}: {value}" for name, value in facts.items()]
    lines += ["", "### doctor", ""]
    lines += [f"- {level} {name}: {message}" for level, name, message in doctor_rows]
    lines += ["", "### results", "", "| Check | Result (pass, fail, n/a) | Notes |", "|---|---|---|"]
    lines += [f"| {row} | | |" for row in REPORT_ROWS]
    return lines
