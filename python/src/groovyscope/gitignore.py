"""Keep the workspace's ``.groovyscope/`` folder out of git: it holds what groovyscope writes for this machine (the
classpath export with absolute paths, the contract baseline, workspace profiles). The entry goes into the
repository's top-level ``.gitignore`` once: a line that already ignores the folder, or any rule git already applies
(``git check-ignore``, global excludes included), counts as present, and a deliberate ``!.groovyscope`` is left as
the team wrote it. Pure apart from the reads, the one ``git check-ignore`` call and the write the caller asks for."""

from __future__ import annotations

import subprocess
from collections.abc import Callable, Sequence
from pathlib import Path
from typing import Literal, TypedDict

from groovyscope import atomic, process

ENTRY = ".groovyscope/"
FOLDER = ".groovyscope"
COMMENT = "# groovyscope: this machine's classpath export; share a contract baseline with git add -f"
# Lines that ignore the folder at the repository root, whatever their spelling.
EQUIVALENT = frozenset(f"{prefix}{FOLDER}{suffix}" for prefix in ("", "/", "**/") for suffix in ("", "/", "/**", "/*"))
CHECK_TIMEOUT_SECONDS = 5.0

State = Literal["no-repository", "present", "negated", "missing", "added"]
Runner = Callable[..., subprocess.CompletedProcess[str]]


class Outcome(TypedDict):
    state: State
    path: str  # the .gitignore that holds or would hold the entry ("" outside a repository)


def repository_root(start: Path) -> Path | None:
    """The nearest folder at or above ``start`` that holds ``.git`` (a folder, or a file in a worktree)."""
    for folder in (start.resolve(), *start.resolve().parents):
        if (folder / ".git").exists():
            return folder
    return None


def _lines(path: Path) -> list[str]:
    try:
        return [line.strip() for line in path.read_text(encoding="utf-8").splitlines()]
    except (OSError, UnicodeDecodeError):
        return []


def listed(lines: Sequence[str]) -> bool:
    """Whether one of the lines ignores the folder."""
    return any(line in EQUIVALENT for line in lines)


def negated(lines: Sequence[str]) -> bool:
    """Whether the team chose to keep (part of) the folder in git."""
    return any(line.startswith("!") and line[1:].lstrip("/").startswith(FOLDER) for line in lines)


def ignored_by_git(repository: Path, run: Runner) -> bool:
    """Whether git already ignores a file in the folder by some rule; ``False`` when git cannot say."""
    try:
        result = run(
            ["git", "check-ignore", "-q", "--no-index", f"{FOLDER}/x"],
            cwd=repository,
            timeout=CHECK_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.SubprocessError):
        return False
    return result.returncode == 0


def hidden(path: Path, run: Runner = process.run_text) -> bool:
    """Whether a commit would leave ``path`` out: git ignores it and does not track it yet. ``False`` outside a
    repository or when git cannot say, so a caller only ever adds a hint."""
    repository = repository_root(path.parent)
    if repository is None:
        return False
    try:
        result = run(["git", "check-ignore", "-q", str(path)], cwd=repository, timeout=CHECK_TIMEOUT_SECONDS)
    except (OSError, subprocess.SubprocessError):
        return False
    return result.returncode == 0


def ensure(start: Path, *, write: bool, run: Runner = process.run_text) -> Outcome:
    """Make sure the repository holding ``start`` ignores ``.groovyscope/``; with ``write`` false, only say."""
    repository = repository_root(start)
    if repository is None:
        return {"state": "no-repository", "path": ""}
    path = repository / ".gitignore"
    lines = _lines(path)
    if negated(lines):
        return {"state": "negated", "path": str(path)}
    if listed(lines) or ignored_by_git(repository, run):
        return {"state": "present", "path": str(path)}
    if not write:
        return {"state": "missing", "path": str(path)}
    try:
        current = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        current = ""
    separator = "" if not current or current.endswith("\n") else "\n"
    gap = "\n" if current.strip() else ""
    atomic.write_text(path, f"{current}{separator}{gap}{COMMENT}\n{ENTRY}\n")
    return {"state": "added", "path": str(path)}
