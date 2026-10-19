from __future__ import annotations

from pathlib import Path

import pytest

from groovyscope import atomic


def test_writes_whole_files_with_the_mode_asked_for(tmp_path: Path) -> None:
    target = tmp_path / "deep" / "config.json"
    atomic.write_text(target, "{}", mode=atomic.PRIVATE_MODE)
    assert target.read_text(encoding="utf-8") == "{}"
    assert target.stat().st_mode & 0o777 == 0o600
    atomic.write_bytes(target, b"[1]")
    assert target.read_bytes() == b"[1]"
    assert [path.name for path in target.parent.iterdir()] == ["config.json"]  # no temporary left behind


def test_an_existing_file_keeps_its_mode_and_a_new_one_follows_the_umask(tmp_path: Path) -> None:
    kept = tmp_path / "kept.txt"
    kept.write_text("old")
    kept.chmod(0o640)
    atomic.write_text(kept, "new")
    assert kept.read_text() == "new" and kept.stat().st_mode & 0o777 == 0o640
    plain = tmp_path / "plain.txt"
    plain.touch()  # what the umask gives any new file
    fresh = tmp_path / "fresh.txt"
    atomic.write_text(fresh, "x")
    assert fresh.stat().st_mode & 0o777 == plain.stat().st_mode & 0o777


def test_no_module_toggles_the_process_umask() -> None:
    package = Path(atomic.__file__).parent
    assert [path.name for path in package.glob("*.py") if "os.umask(" in path.read_text(encoding="utf-8")] == []


def test_a_failed_rename_leaves_no_temporary_file(tmp_path: Path) -> None:
    occupied = tmp_path / "occupied"
    (occupied / "inside").mkdir(parents=True)
    with pytest.raises(OSError):
        atomic.write_text(occupied, "x")
    assert sorted(path.name for path in tmp_path.iterdir()) == ["occupied"]
