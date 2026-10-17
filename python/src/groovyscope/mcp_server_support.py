"""Helpers shared by the MCP tool modules (paths, URIs, clipping) and the call/paging signatures."""

from __future__ import annotations

import urllib.parse
from collections.abc import Callable, Sequence
from pathlib import Path
from typing import Any

Call = Callable[[str, dict[str, Any]], Any]
Paged = Callable[[Sequence[Any], int | None, int], dict[str, Any]]


def relative_path(uri: str, root: Path) -> str:
    path = Path(urllib.parse.unquote(urllib.parse.urlparse(uri).path))
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


def clip(text: Any, limit: int) -> Any:
    """Long text cut to ``limit`` characters with an ellipsis; anything else unchanged."""
    if not isinstance(text, str) or limit <= 0 or len(text) <= limit:
        return text
    return text[: max(1, limit - 1)] + "…"
