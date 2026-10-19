"""Child processes whose output is read as text: always UTF-8, and bytes that are not UTF-8 (a file name from
another locale in git's output) are kept as surrogate escapes instead of failing the caller."""

from __future__ import annotations

import subprocess
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

TEXT_ENCODING = "utf-8"
TEXT_ERRORS = "surrogateescape"


def run_text(
    command: Sequence[str],
    *,
    timeout: float,
    cwd: Path | None = None,
    env: Mapping[str, str] | None = None,
    check: bool = False,
) -> subprocess.CompletedProcess[str]:
    """``subprocess.run`` with captured output decoded as UTF-8 (surrogate escapes for anything else), never a
    shell. Raises what ``subprocess.run`` raises (``OSError``, ``TimeoutExpired``, ``CalledProcessError``)."""
    return subprocess.run(
        list(command),
        cwd=cwd,
        env=None if env is None else dict(env),
        capture_output=True,
        encoding=TEXT_ENCODING,
        errors=TEXT_ERRORS,
        timeout=timeout,
        check=check,
    )


def text_options() -> dict[str, Any]:
    """The decoding arguments for a ``Popen`` whose output is read as text."""
    return {"encoding": TEXT_ENCODING, "errors": TEXT_ERRORS}
