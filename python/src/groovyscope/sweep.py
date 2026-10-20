"""What grows under the cache directory and what `doctor` reports and `checkup --fix` removes: rotated daemon
logs beyond the kept count, Class Data Sharing archives nobody used for a while, heap dumps older than a week,
everything a workspace root left behind once the root itself is gone (its logs, state, client and hook files, its
engine configuration record and its symbol store), symbol stores no root claims that nobody touched for a month,
and the numbered Kiro backups between the first and the newest. A live root's symbol store stays (it is rewritten
in place). Everything here is pure apart from the listing and the deletion the caller asks for."""

from __future__ import annotations

import contextlib
import json
import re
import time
from collections.abc import Iterable
from pathlib import Path
from typing import TypedDict

DAY_SECONDS = 86400.0
STALE_ARCHIVE_DAYS = 14
STALE_DUMP_DAYS = 7
STALE_STORE_DAYS = 30
KEY_PATTERN = re.compile(r"^[0-9a-f]{16}$")
NUMBERED_BACKUP = re.compile(r"^(?P<name>.+)\.bak\.(?P<number>\d+)$")


class Reclaimable(TypedDict):
    """One file the sweep would remove."""

    path: str
    bytes: int
    why: str


def inventory(
    cache: Path, *, logs_kept: int, now: float | None = None, kiro_home: Path | None = None
) -> list[Reclaimable]:
    """The files that can go, sorted by path: under ``cache``, and the surplus Kiro backups of the recorded roots
    and, when ``kiro_home`` is given, of the user's own Kiro settings there (``~/.kiro`` or ``KIRO_HOME``)."""
    moment = time.time() if now is None else now
    roots = recorded_roots(cache)
    found: list[Reclaimable] = []
    found.extend(_rotated_logs(cache / "daemon", logs_kept))
    found.extend(_stale(cache / "cds", "*.jsa", STALE_ARCHIVE_DAYS, moment, "CDS archive not used for a fortnight"))
    found.extend(_stale(cache / "dumps", "*.hprof", STALE_DUMP_DAYS, moment, "heap dump older than a week"))
    found.extend(_deleted_roots(cache, roots))
    found.extend(_orphan_stores(cache, roots, moment))
    kiro_dirs = [root / ".kiro" for root in roots.values() if root.is_dir()]
    if kiro_home is not None:
        kiro_dirs.append(kiro_home)
    for folder in dict.fromkeys(kiro_dirs):
        found.extend(_surplus_backups(folder))
    unique = {item["path"]: item for item in found}
    return sorted(unique.values(), key=lambda item: item["path"])


def recorded_roots(cache: Path) -> dict[str, Path]:
    """Workspace key to root, from the engine configuration records the CLI writes (``config/<key>.json``)."""
    roots: dict[str, Path] = {}
    folder = cache / "config"
    if not folder.is_dir():
        return roots
    for record in folder.glob("*.json"):
        try:
            root = json.loads(record.read_text(encoding="utf-8")).get("root")
        except (OSError, ValueError, AttributeError):
            continue
        if isinstance(root, str) and root and KEY_PATTERN.match(record.stem):
            roots[record.stem] = Path(root)
    return roots


def _item(path: Path, why: str) -> Reclaimable:
    return {"path": str(path), "bytes": path.stat().st_size, "why": why}


def _deleted_roots(cache: Path, roots: dict[str, Path]) -> Iterable[Reclaimable]:
    """Every file a key keeps when its recorded root no longer exists."""
    for key, root in roots.items():
        if root.exists():
            continue
        why = f"left by a deleted workspace root ({root})"
        candidates = [
            *(cache / "daemon").glob(f"{key}.*"),
            cache / "hooks" / f"{key}.baseline.json",
            cache / "config" / f"{key}.json",
        ]
        store = cache / key
        if store.is_dir():
            candidates.extend(store.iterdir())
        for path in candidates:
            if path.is_file():
                yield _item(path, why)


def _orphan_stores(cache: Path, roots: dict[str, Path], now: float) -> Iterable[Reclaimable]:
    """Symbol stores no recorded root claims, untouched for a month."""
    if not cache.is_dir():
        return
    for store in cache.iterdir():
        if not store.is_dir() or not KEY_PATTERN.match(store.name) or store.name in roots:
            continue
        files = [path for path in store.iterdir() if path.is_file()]
        if files and all(now - path.stat().st_mtime > STALE_STORE_DAYS * DAY_SECONDS for path in files):
            for path in files:
                yield _item(path, "orphan symbol store, no workspace root recorded and untouched for a month")


def _surplus_backups(folder: Path) -> Iterable[Reclaimable]:
    """Numbered Kiro backups (``<name>.bak.N``) but the newest of each name; ``<name>.bak`` (the original) stays."""
    if not folder.is_dir():
        return
    numbered: dict[Path, list[tuple[int, Path]]] = {}
    for path in folder.rglob("*.bak.*"):
        match = NUMBERED_BACKUP.match(path.name)
        if match and path.is_file():
            numbered.setdefault(path.with_name(match.group("name")), []).append((int(match.group("number")), path))
    for backups in numbered.values():
        for _number, path in sorted(backups)[:-1]:
            yield _item(path, "older numbered Kiro backup (the first .bak and the newest are kept)")


def _rotated_logs(folder: Path, kept: int) -> Iterable[Reclaimable]:
    if not folder.is_dir():
        return
    for path in folder.iterdir():
        suffix = path.suffix[1:]
        if path.is_file() and suffix.isdigit() and int(suffix) > max(1, kept):
            yield _item(path, f"rotated log beyond daemon.logsKept ({kept})")


def _stale(folder: Path, pattern: str, days: int, now: float, why: str) -> Iterable[Reclaimable]:
    if not folder.is_dir():
        return
    for path in folder.glob(pattern):
        if path.is_file() and now - path.stat().st_mtime > days * DAY_SECONDS:
            yield _item(path, why)


def remove(items: Iterable[Reclaimable]) -> list[str]:
    """Deletes the listed files, and a symbol store folder they leave empty; the paths that could not be removed."""
    failed: list[str] = []
    for item in items:
        path = Path(item["path"])
        try:
            path.unlink(missing_ok=True)
        except OSError:
            failed.append(item["path"])
            continue
        if KEY_PATTERN.match(path.parent.name):
            # not empty yet: another file of the store follows, or a live one stays
            with contextlib.suppress(OSError):
                path.parent.rmdir()
    return failed


def summary(items: list[Reclaimable]) -> str:
    total = sum(item["bytes"] for item in items)
    return f"{len(items)} file(s), {total // (1024 * 1024)} MB reclaimable under the cache"
