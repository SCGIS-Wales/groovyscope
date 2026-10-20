from __future__ import annotations

import json
import os
import time
from pathlib import Path

from groovyscope import sweep


def test_inventory_lists_rotated_logs_stale_archives_and_old_dumps(tmp_path: Path) -> None:
    now = time.time()
    daemon_dir = tmp_path / "daemon"
    daemon_dir.mkdir()
    for name in ("k.log", "k.log.1", "k.log.2", "k.log.3", "k.log.4"):
        (daemon_dir / name).write_text("x")
    cds = tmp_path / "cds"
    cds.mkdir()
    fresh = cds / "engine-new.jsa"
    fresh.write_text("a")
    stale = cds / "engine-old.jsa"
    stale.write_text("bb")
    os.utime(stale, (now - 20 * 86400, now - 20 * 86400))
    dumps = tmp_path / "dumps"
    dumps.mkdir()
    old_dump = dumps / "java_pid1.hprof"
    old_dump.write_text("ccc")
    os.utime(old_dump, (now - 8 * 86400, now - 8 * 86400))
    new_dump = dumps / "java_pid2.hprof"
    new_dump.write_text("d")

    items = sweep.inventory(tmp_path, logs_kept=2, now=now)
    assert sorted(Path(item["path"]).name for item in items) == [
        "engine-old.jsa",
        "java_pid1.hprof",
        "k.log.3",
        "k.log.4",
    ]
    assert sweep.summary(items).startswith("4 file(s), 0 MB reclaimable")
    assert sweep.remove(items) == []
    assert fresh.exists() and new_dump.exists() and (daemon_dir / "k.log.2").exists()
    assert not stale.exists() and not old_dump.exists()
    assert sweep.inventory(tmp_path, logs_kept=2, now=now) == []
    assert sweep.inventory(tmp_path / "nowhere", logs_kept=2) == []


def _record(cache: Path, key: str, root: Path) -> None:
    (cache / "config").mkdir(parents=True, exist_ok=True)
    (cache / "config" / f"{key}.json").write_text(json.dumps({"root": str(root)}))


def test_inventory_drops_what_deleted_roots_left_behind(tmp_path: Path) -> None:
    now = time.time()
    cache, home = tmp_path / "cache", tmp_path / "home"
    live, gone = tmp_path / "live", tmp_path / "gone"
    live.mkdir()
    _record(cache, "a" * 16, live)
    _record(cache, "b" * 16, gone)
    daemon_dir = cache / "daemon"
    daemon_dir.mkdir()
    for key in ("a" * 16, "b" * 16):
        for suffix in (".log", ".log.1", ".state.json", ".client.json"):
            (daemon_dir / f"{key}{suffix}").write_text("x")
        (cache / key).mkdir()
        (cache / key / "symbols.db").write_text("db")
        (cache / key / "symbols.db-wal").write_text("w")
    (cache / "hooks").mkdir()
    (cache / "hooks" / f"{'b' * 16}.baseline.json").write_text("{}")
    orphan, recent = cache / ("c" * 16), cache / ("d" * 16)
    for store in (orphan, recent):
        store.mkdir()
        (store / "symbols.db").write_text("db")
    old = now - 40 * 86400
    os.utime(orphan / "symbols.db", (old, old))

    items = sweep.inventory(cache, logs_kept=3, now=now, kiro_home=home / ".kiro")
    names = {str(Path(item["path"]).relative_to(cache)) for item in items}
    gone_key = "b" * 16
    assert names == {
        f"daemon/{gone_key}.log",
        f"daemon/{gone_key}.log.1",
        f"daemon/{gone_key}.state.json",
        f"daemon/{gone_key}.client.json",
        f"hooks/{gone_key}.baseline.json",
        f"config/{gone_key}.json",
        f"{gone_key}/symbols.db",
        f"{gone_key}/symbols.db-wal",
        f"{'c' * 16}/symbols.db",
    }
    assert all("deleted" in item["why"] or "orphan" in item["why"] for item in items)
    assert sweep.remove(items) == []
    assert not (cache / gone_key).exists() and not orphan.exists()
    assert (cache / ("a" * 16) / "symbols.db").exists() and (recent / "symbols.db").exists()


def test_inventory_keeps_the_first_and_newest_kiro_backup(tmp_path: Path) -> None:
    cache, home, root = tmp_path / "cache", tmp_path / "home", tmp_path / "project"
    _record(cache, "a" * 16, root)
    agents = root / ".kiro" / "agents"
    agents.mkdir(parents=True)
    for name in ("groovy.json.bak", "groovy.json.bak.1", "groovy.json.bak.2", "groovy.json.bak.3", "other.json.bak.1"):
        (agents / name).write_text("{}")
    global_steering = home / ".kiro" / "steering"
    global_steering.mkdir(parents=True)
    for name in ("groovy.md.bak.1", "groovy.md.bak.2"):
        (global_steering / name).write_text("x")

    items = sweep.inventory(cache, logs_kept=3, kiro_home=home / ".kiro")
    assert sorted(Path(item["path"]).name for item in items) == [
        "groovy.json.bak.1",
        "groovy.json.bak.2",
        "groovy.md.bak.1",
    ]
    assert all("backup" in item["why"] for item in items)
