from __future__ import annotations

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
