"""Whole-file writes that a crash or a second writer never leaves half done."""

from __future__ import annotations

import contextlib
import os
import secrets
import stat
from collections.abc import Callable
from pathlib import Path

NEW_FILE_MODE = 0o666  # the process umask narrows it, as for any new file
PRIVATE_MODE = 0o600


def write_bytes(target: Path, data: bytes, *, mode: int | None = None, guard: Callable[[], None] | None = None) -> None:
    """Writes ``data`` to a temporary file next to ``target`` and renames it over ``target``. The temporary name is
    unique per call (two writers never rename each other's half) and created exclusively, and it is removed when
    anything fails. ``mode`` is the file's mode; without one an existing file keeps its own and a new one gets
    ``0o666`` narrowed by the umask (applied by the kernel: nothing reads or toggles the process umask). ``guard``
    runs just before the rename; whatever it raises leaves ``target`` as it was."""
    target.parent.mkdir(parents=True, exist_ok=True)
    keep: int | None = None
    if mode is None:
        with contextlib.suppress(FileNotFoundError):
            keep = stat.S_IMODE(target.stat().st_mode)
    temporary = target.with_name(f".{target.name}.{secrets.token_hex(8)}.tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, NEW_FILE_MODE if mode is None else mode)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(data)
        if keep is not None:
            temporary.chmod(keep)
        if guard is not None:
            guard()
        os.replace(temporary, target)  # noqa: PTH105 - os.replace keeps the intent (atomic rename) explicit
    finally:
        with contextlib.suppress(FileNotFoundError):
            temporary.unlink()


def write_text(target: Path, text: str, *, mode: int | None = None, guard: Callable[[], None] | None = None) -> None:
    """``write_bytes`` for UTF-8 text."""
    write_bytes(target, text.encode("utf-8"), mode=mode, guard=guard)
