#!/usr/bin/env python3
"""Build the benchmark corpus the package ships: CodeNarc's ``src/main`` tree as one deterministic zip.

Usage: python scripts/bundle_benchmark.py --checkout PATH [--version 4.0.0] [--out DIR]

``PATH`` is a CodeNarc source checkout (``git clone --branch v4.0.0 https://github.com/CodeNarc/CodeNarc``). The
zip holds ``src/main/**`` plus CodeNarc's LICENSE.txt and NOTICE.txt (Apache 2.0), with fixed timestamps and sorted
entries so the same checkout always gives the same bytes. A manifest next to it records the version, the SHA-256
and the file count; ``groovyscope.benchmark`` reads both.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT_DIR = REPO / "python" / "src" / "groovyscope" / "_benchmark"
FIXED_TIME = (2026, 1, 1, 0, 0, 0)
SOURCE = "https://github.com/CodeNarc/CodeNarc"
SKIPPED_SUFFIXES = {".class", ".jar", ".bak"}


def corpus_files(checkout: Path) -> list[Path]:
    """Every regular file under ``src/main`` plus the licence files, sorted for determinism."""
    main = checkout / "src" / "main"
    if not main.is_dir():
        msg = f"{checkout} has no src/main folder; point --checkout at a CodeNarc source checkout"
        raise ValueError(msg)
    files = [p for p in main.rglob("*") if p.is_file() and p.suffix not in SKIPPED_SUFFIXES]
    # The corpus is CodeNarc's source, Apache-2.0: it ships only with its licence and notice.
    for name in ("LICENSE.txt", "NOTICE.txt"):
        if not (checkout / name).is_file():
            msg = f"{checkout} has no {name}; the corpus is redistributed only with CodeNarc's licence and notice"
            raise ValueError(msg)
        files.append(checkout / name)
    return sorted(files)


def write_zip(checkout: Path, files: list[Path], target: Path) -> int:
    target.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for file in files:
            info = zipfile.ZipInfo(file.relative_to(checkout).as_posix(), date_time=FIXED_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, file.read_bytes())
    return len(files)


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--checkout", required=True, type=Path, help="CodeNarc source checkout")
    parser.add_argument("--version", default="4.0.0", help="CodeNarc version the checkout holds")
    parser.add_argument("--out", type=Path, default=OUT_DIR, help="package folder for the zip and manifest")
    args = parser.parse_args(argv)
    checkout = args.checkout.resolve()
    try:
        files = corpus_files(checkout)
    except ValueError as exc:
        print(f"bundle_benchmark: {exc}", file=sys.stderr)
        return 1
    zip_name = f"codenarc-{args.version}-src-main.zip"
    target = args.out / zip_name
    count = write_zip(checkout, files, target)
    manifest = {
        "name": "CodeNarc",
        "version": args.version,
        "zip": zip_name,
        "sha256": sha256_of(target),
        "files": count,
        "source": SOURCE,
        "license": "Apache-2.0",
        "roots": ["src/main/groovy", "src/main/java"],
    }
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"bundled {count} files from {checkout} into {target} ({target.stat().st_size // 1024} KiB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
