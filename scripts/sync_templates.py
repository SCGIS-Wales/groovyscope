#!/usr/bin/env python3
"""Regenerate the repository's copies of the package templates, the only source.

Usage: python scripts/sync_templates.py [--check] [--repo DIR]

``python/src/groovyscope/_templates`` holds the Kiro assets and the configuration groovyscope ships. The repository
keeps readable copies at its root: ``kiro/`` and ``config/`` (with ``config/defaults.yaml`` from the package
defaults), and the wheel keeps copies of the root licence files in ``python/``. The engine takes its CodeNarc
profiles and practices catalogue from the templates when Gradle builds it, so it keeps no copy. With ``--check``
nothing is written: the copies that differ or are missing are listed and the exit status is 1 when there is any.
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
LICENCE_FILES = ("LICENSE", "NOTICE", "THIRD-PARTY-NOTICES.md")


def pairs(repo: Path) -> list[tuple[Path, Path]]:
    """Every (source, copy) pair the repository keeps, sources first in a stable order."""
    package = repo / "python" / "src" / "groovyscope"
    templates = package / "_templates"
    found: list[tuple[Path, Path]] = []
    for name in ("kiro", "config"):
        source = templates / name
        for file in sorted(p for p in source.rglob("*") if p.is_file()):
            found.append((file, repo / name / file.relative_to(source)))
    found.append((package / "defaults.yaml", repo / "config" / "defaults.yaml"))
    for name in LICENCE_FILES:
        found.append((repo / name, repo / "python" / name))
    return found


def drifted(found: list[tuple[Path, Path]]) -> list[tuple[Path, Path]]:
    """The pairs whose copy is missing or differs from its source."""
    return [(source, copy) for source, copy in found if not copy.is_file() or copy.read_bytes() != source.read_bytes()]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="list drifted copies and fail instead of writing them")
    parser.add_argument("--repo", type=Path, default=REPO)
    args = parser.parse_args(argv)
    repo: Path = args.repo
    drift = drifted(pairs(repo))
    if args.check:
        for source, copy in drift:
            print(f"sync_templates: {copy.relative_to(repo)} differs from {source.relative_to(repo)}", file=sys.stderr)
        if drift:
            print("sync_templates: run python3 scripts/sync_templates.py to update the copies", file=sys.stderr)
        return 1 if drift else 0
    for source, copy in drift:
        copy.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, copy)
        print(f"updated {copy.relative_to(repo)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
