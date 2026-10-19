"""Whole-file writes that a crash or a second writer never leaves half done."""

from __future__ import annotations

import contextlib
import os
import secrets
from pathlib import Path

DEFAULT_MODE = 0o666  # the process umask narrows it, as for any new file
PRIVATE_MODE = 0o600


def write_bytes(target: Path, data: bytes, *, mode: int = DEFAULT_MODE) -> None:
    """Writes ``data`` to a temporary file next to ``target`` and renames it over ``target``. The temporary name is
    unique per call (two writers never rename each other's half), it is created exclusively with ``mode`` (the
    kernel applies the umask; nothing toggles it), and it is removed when anything fails."""
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(f".{target.name}.{secrets.token_hex(8)}.tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(data)
        os.replace(temporary, target)  # noqa: PTH105 - os.replace keeps the intent (atomic rename) explicit
    finally:
        with contextlib.suppress(FileNotFoundError):
            temporary.unlink()


def write_text(target: Path, text: str, *, mode: int = DEFAULT_MODE) -> None:
    """``write_bytes`` for UTF-8 text."""
    write_bytes(target, text.encode("utf-8"), mode=mode)
