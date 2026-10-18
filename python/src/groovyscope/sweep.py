"""What grows under the cache directory and what `doctor` reports and `checkup --fix` removes: rotated daemon
logs beyond the kept count, Class Data Sharing archives nobody used for a while, heap dumps older than a week.
Symbol stores stay (one per workspace root, rewritten in place). Everything here is pure apart from the listing
and the deletion the caller asks for."""

from __future__ import annotations

import time
from collections.abc import Iterable
from pathlib import Path
from typing import TypedDict

DAY_SECONDS = 86400.0
STALE_ARCHIVE_DAYS = 14
STALE_DUMP_DAYS = 7


class Reclaimable(TypedDict):
    """One file the sweep would remove."""

    path: str
    bytes: int
    why: str


def inventory(cache: Path, *, logs_kept: int, now: float | None = None) -> list[Reclaimable]:
    """The files under ``cache`` that can go, oldest first."""
    moment = time.time() if now is None else now
    found: list[Reclaimable] = []
    found.extend(_rotated_logs(cache / "daemon", logs_kept))
    found.extend(_stale(cache / "cds", "*.jsa", STALE_ARCHIVE_DAYS, moment, "CDS archive not used for a fortnight"))
    found.extend(_stale(cache / "dumps", "*.hprof", STALE_DUMP_DAYS, moment, "heap dump older than a week"))
    return sorted(found, key=lambda item: item["path"])


def _rotated_logs(folder: Path, kept: int) -> Iterable[Reclaimable]:
    if not folder.is_dir():
        return
    for path in folder.iterdir():
        suffix = path.suffix[1:]
        if path.is_file() and suffix.isdigit() and int(suffix) > max(1, kept):
            why = f"rotated log beyond daemon.logsKept ({kept})"
            yield {"path": str(path), "bytes": path.stat().st_size, "why": why}


def _stale(folder: Path, pattern: str, days: int, now: float, why: str) -> Iterable[Reclaimable]:
    if not folder.is_dir():
        return
    for path in folder.glob(pattern):
        if path.is_file() and now - path.stat().st_mtime > days * DAY_SECONDS:
            yield {"path": str(path), "bytes": path.stat().st_size, "why": why}


def remove(items: Iterable[Reclaimable]) -> list[str]:
    """Deletes the listed files; the paths that could not be removed."""
    failed: list[str] = []
    for item in items:
        try:
            Path(item["path"]).unlink(missing_ok=True)
        except OSError:
            failed.append(item["path"])
    return failed


def summary(items: list[Reclaimable]) -> str:
    total = sum(item["bytes"] for item in items)
    return f"{len(items)} file(s), {total // (1024 * 1024)} MB reclaimable under the cache"
