"""Workspace paths and file URIs, the guard that keeps a requested path inside the workspace, and the helpers
the MCP tool modules share (clipping, the call and paging signatures)."""

from __future__ import annotations

import urllib.parse
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any

# The Groovy source suffixes groovyscope reads, reviews and may edit (a ``Jenkinsfile*`` counts by its name).
GROOVY_SUFFIXES = (".groovy", ".gvy", ".gy", ".gsh", ".gradle")

Call = Callable[[str, dict[str, Any]], Any]
Paged = Callable[[Sequence[Any], int | None, int], dict[str, Any]]


def relative_path(uri: str, root: Path) -> str:
    """A file URI as a path relative to ``root`` (the absolute path when it lies outside)."""
    path = uri_to_path(uri)
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.as_posix()


def to_uri(root: Path, path: str) -> str:
    """The file URI of a workspace-relative path. Paths that leave the workspace are refused."""
    target = (root / path).resolve()
    if not target.is_relative_to(root.resolve()):
        msg = f"{path} is outside the workspace"
        raise ValueError(msg)
    return target.as_uri()


def to_uris(root: Path, paths: Sequence[Path | str]) -> list[str]:
    """The file URIs of ``paths`` (relative to ``root``, or absolute). A path outside the workspace is refused with
    ``ValueError``, as in ``to_uri``."""
    return [to_uri(root, str(path)) for path in paths]


def uri_to_path(uri: str) -> Path:
    """The local path a file URI names."""
    return Path(urllib.parse.unquote(urllib.parse.urlparse(uri).path))


# The tool arguments that name workspace files or folders, and how many missing ones an answer names.
PATH_ARGUMENTS = ("path", "paths")
MISSING_SHOWN = 5


def missing_paths(root: Path, arguments: Mapping[str, Any], exists: Callable[[Path], bool] = Path.exists) -> list[str]:
    """The ``path`` and ``paths`` arguments of a tool call that name nothing in the workspace, or leave it.

    Checked before any tool runs: a file the engine does not know would otherwise come back with no findings, a
    clean answer about nothing (a client started in a neighbouring repository asks for that repository's files)."""
    named: list[str] = []
    for key in PATH_ARGUMENTS:
        value = arguments.get(key)
        if isinstance(value, str):
            named.append(value)
        elif isinstance(value, list):
            named += [str(item) for item in value]
    base = root.resolve()
    missing: list[str] = []
    for path in named:
        target = (base / path).resolve()
        if not target.is_relative_to(base) or not exists(target):
            missing.append(path)
    return missing


def missing_paths_answer(root: Path, missing: Sequence[str]) -> dict[str, Any]:
    """The answer instead of a tool's result when it names files the workspace does not have."""
    rest = len(missing) - MISSING_SHOWN
    shown = ", ".join(missing[:MISSING_SHOWN]) + (f" and {rest} more" if rest > 0 else "")
    return {
        "error": (
            f"not found in the workspace {root}: {shown}. Nothing was checked. groovyscope answers for the folder "
            "the client was started in: start it in the repository you are editing, or give paths relative to "
            f"{root}."
        ),
        "missing": list(missing),
        "workspace": str(root),
    }


def clip(text: Any, limit: int) -> Any:
    """Long text cut to ``limit`` characters with an ellipsis; anything else unchanged."""
    if not isinstance(text, str) or limit <= 0 or len(text) <= limit:
        return text
    return text[: max(1, limit - 1)] + "…"
