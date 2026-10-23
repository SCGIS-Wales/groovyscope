"""Planned writes: stored privately, matched to the write that applies them, and checked after it lands."""

from __future__ import annotations

import json
from pathlib import Path

from groovyscope import plans

BEFORE = "class A {\n    int a() { 1 }\n}\n"
AFTER = "class A {\n    int a() { 2 }\n}\n"


def make(path: Path, now: float = 100.0) -> plans.Plan:
    return plans.make(path, BEFORE, AFTER, old_str="{ 1 }", new_str="{ 2 }", now=now)


def test_a_plan_has_a_stable_id_and_the_hashes_of_both_texts(tmp_path: Path) -> None:
    plan = make(tmp_path / "A.groovy")
    assert plan["plan_id"] == make(tmp_path / "A.groovy", now=5.0)["plan_id"]
    assert plan["before_hash"] == plans.digest(BEFORE) and plan["after_hash"] == plans.digest(AFTER)
    assert plan["path"] == str((tmp_path / "A.groovy").resolve())


def test_save_keeps_the_newest_fresh_plans_privately(tmp_path: Path) -> None:
    store, path = tmp_path / "store", tmp_path / "A.groovy"
    for n in range(4):
        plan = plans.make(path, BEFORE, AFTER + str(n), old_str="x", new_str=str(n), now=100.0 + n)
        plans.save(store, plan, kept=2, clock=lambda: 110.0, ttl_minutes=60)
    loaded = plans.load(store, path)
    assert [p["new_str"] for p in loaded] == ["3", "2"]
    assert (plans.store_file(store, path).stat().st_mode & 0o777) == 0o600
    plans.save(store, loaded[0], kept=5, clock=lambda: 110.0, ttl_minutes=60)  # the same id replaces, not adds
    assert len(plans.load(store, path)) == 2
    assert plans.fresh(loaded, now=100.0 + 3 + 61 * 60, ttl_minutes=60) == []


def test_load_ignores_a_missing_or_malformed_store(tmp_path: Path) -> None:
    path = tmp_path / "A.groovy"
    assert plans.load(tmp_path, path) == []
    plans.store_file(tmp_path, path).write_text("{}", encoding="utf-8")
    assert plans.load(tmp_path, path) == []
    plans.store_file(tmp_path, path).write_text(json.dumps([{"plan_id": "x"}, 3]), encoding="utf-8")
    assert plans.load(tmp_path, path) == []


def test_matching_needs_the_same_text_and_the_same_replacement(tmp_path: Path) -> None:
    store, path = tmp_path / "store", tmp_path / "A.groovy"
    plans.save(store, make(path), kept=5, clock=lambda: 100.0, ttl_minutes=60)

    def match(current: str, old: str, new: str, now: float = 100.0) -> plans.Plan | None:
        return plans.matching(store, path, current, old, new, clock=lambda: now, ttl_minutes=60)

    assert match(BEFORE, "{ 1 }", "{ 2 }") is not None
    assert match(BEFORE + "\n", "{ 1 }", "{ 2 }") is None  # the file changed since the plan was made
    assert match(BEFORE, "{ 1 }", "{ 3 }") is None
    assert match(BEFORE, "{ 1 }", "{ 2 }", now=100.0 + 3601) is None  # stale


def test_outcome_tells_applied_from_drifted(tmp_path: Path) -> None:
    store, path = tmp_path / "store", tmp_path / "A.groovy"
    clock = lambda: 100.0  # noqa: E731
    assert plans.outcome(store, path, AFTER, clock=clock, ttl_minutes=60) is None
    plans.save(store, make(path), kept=5, clock=clock, ttl_minutes=60)
    assert plans.outcome(store, path, AFTER, clock=clock, ttl_minutes=60) == ("applied", make(path))
    found = plans.outcome(store, path, "something else", clock=clock, ttl_minutes=60)
    assert found is not None and found[0] == "drifted"
