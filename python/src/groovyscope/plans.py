"""Planned writes: an edit groovyscope prepared (and gated) for the agent to apply with Kiro's own ``write``, so the
user still sees Kiro's diff and answers its y, n, t prompt.

A plan is the exact ``str_replace`` (``old_str``, ``new_str``) for one file, with hashes of the text before and
after. The ``PreToolUse`` hook recognises a write that applies a stored plan to the text it was made for, and the
``PostToolUse`` hook says whether the file now holds what was planned. Plans live under the cache, one JSON file
per path, private to the user; the newest ``edits.plansKept`` are kept, and plans older than
``edits.planTtlMinutes`` count for nothing. Pure apart from the store's reads and writes, with the clock injected.
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import TypedDict, cast

from groovyscope import atomic, daemon

Clock = Callable[[], float]


class Plan(TypedDict):
    plan_id: str
    path: str  # absolute, resolved
    before_hash: str
    old_str: str
    new_str: str
    after_hash: str
    created: float  # seconds since the epoch


def digest(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def plan_id(path: str, before: str, after: str) -> str:
    """The SHA-256 of the path, the text before and the text after: the same edit of the same text has one id."""
    return hashlib.sha256("\0".join((path, before, after)).encode("utf-8")).hexdigest()


def make(path: Path, before: str, after: str, *, old_str: str, new_str: str, now: float) -> Plan:
    resolved = str(path.resolve())
    return {
        "plan_id": plan_id(resolved, before, after),
        "path": resolved,
        "before_hash": digest(before),
        "old_str": old_str,
        "new_str": new_str,
        "after_hash": digest(after),
        "created": now,
    }


def store_for(root: Path, environ: Mapping[str, str]) -> Path:
    """The plan store of a workspace: under the cache, one folder per workspace."""
    return daemon.cache_dir(environ) / "plans" / daemon.workspace_key(root)


def store_file(store: Path, path: Path) -> Path:
    """The file holding the plans of ``path``: its name is the hash of the resolved path."""
    return store / f"{digest(str(path.resolve()))[:32]}.json"


def load(store: Path, path: Path) -> list[Plan]:
    """The plans stored for ``path``, newest first; none when the file is missing or unreadable."""
    try:
        loaded = json.loads(store_file(store, path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    if not isinstance(loaded, list):
        return []
    keys = set(Plan.__annotations__)
    plans = [cast("Plan", item) for item in loaded if isinstance(item, dict) and keys <= set(item)]
    return sorted(plans, key=lambda plan: -float(plan["created"]))


def fresh(plans: Sequence[Plan], now: float, ttl_minutes: float) -> list[Plan]:
    return [plan for plan in plans if now - float(plan["created"]) <= ttl_minutes * 60]


def save(store: Path, plan: Plan, *, kept: int, clock: Clock, ttl_minutes: float) -> None:
    """Add ``plan`` (replacing one with the same id), keep the newest ``kept`` fresh ones, write privately."""
    path = Path(plan["path"])
    others = [item for item in load(store, path) if item["plan_id"] != plan["plan_id"]]
    plans = fresh([plan, *others], clock(), ttl_minutes)[: max(1, kept)]
    store.mkdir(parents=True, exist_ok=True)
    atomic.write_text(store_file(store, path), json.dumps(plans, sort_keys=True) + "\n", mode=atomic.PRIVATE_MODE)


def matching(
    store: Path, path: Path, current: str, old_str: str, new_str: str, *, clock: Clock, ttl_minutes: float
) -> Plan | None:
    """The fresh plan that this ``str_replace`` of ``path`` applies to the text it was made for, or None."""
    before = digest(current)
    for plan in fresh(load(store, path), clock(), ttl_minutes):
        if plan["before_hash"] == before and plan["old_str"] == old_str and plan["new_str"] == new_str:
            return plan
    return None


def outcome(store: Path, path: Path, written: str, *, clock: Clock, ttl_minutes: float) -> tuple[str, Plan] | None:
    """After a write: ``applied`` when the file holds a fresh plan's result, ``drifted`` when the newest fresh plan
    for the file was not what landed; None when the file has no fresh plan."""
    plans = fresh(load(store, path), clock(), ttl_minutes)
    if not plans:
        return None
    after = digest(written)
    for plan in plans:
        if plan["after_hash"] == after:
            return ("applied", plan)
    return ("drifted", plans[0])
