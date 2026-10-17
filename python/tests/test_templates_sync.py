"""The repo-level kiro/ and config/ directories must match the copies shipped inside the package."""

from __future__ import annotations

from pathlib import Path

import pytest

PACKAGE = Path(__file__).resolve().parents[1] / "src" / "groovyscope"
REPO = Path(__file__).resolve().parents[2]


def pairs() -> list[tuple[Path, Path]]:
    mapping = [
        (PACKAGE / "_templates" / "kiro", REPO / "kiro"),
        (PACKAGE / "_templates" / "config", REPO / "config"),
    ]
    found: list[tuple[Path, Path]] = []
    for source, mirror in mapping:
        for file in sorted(p for p in source.rglob("*") if p.is_file()):
            found.append((file, mirror / file.relative_to(source)))
    found.append((PACKAGE / "defaults.yaml", REPO / "config" / "defaults.yaml"))
    return found


@pytest.mark.skipif(not (REPO / "kiro").is_dir(), reason="repository checkout required")
@pytest.mark.parametrize(("packaged", "mirrored"), pairs(), ids=lambda p: str(p.name))
def test_repo_copy_matches_package_copy(packaged: Path, mirrored: Path) -> None:
    assert mirrored.is_file(), f"missing mirror {mirrored}; copy {packaged} there"
    assert mirrored.read_text(encoding="utf-8") == packaged.read_text(encoding="utf-8")
