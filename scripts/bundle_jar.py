#!/usr/bin/env python3
"""Copy the shaded engine JAR into the Python package and write its manifest (version, file name, SHA-256).

Usage: python scripts/bundle_jar.py [--jar PATH] [--version X.Y.Z] [--jar-dir DIR]
Defaults: the newest engine/build/libs/groovyscope-engine-*-all.jar; version parsed from the file name.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
ENGINE_LIBS = REPO / "engine" / "build" / "libs"
JAR_DIR = REPO / "python" / "src" / "groovyscope" / "_jar"
NAME_RE = re.compile(r"groovyscope-engine-(?P<version>.+)-all\.jar$")


def newest_jar(libs: Path) -> Path | None:
    candidates = sorted(
        libs.glob("groovyscope-engine-*-all.jar"), key=lambda p: p.stat().st_mtime
    )
    return candidates[-1] if candidates else None


def version_from_name(jar: Path) -> str | None:
    match = NAME_RE.search(jar.name)
    return match.group("version") if match else None


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def bundle(jar: Path, version: str, jar_dir: Path) -> Path:
    jar_dir.mkdir(parents=True, exist_ok=True)
    target = jar_dir / jar.name
    for stale in jar_dir.glob("*.jar"):
        if stale.resolve() != jar.resolve():
            stale.unlink()
    if target.resolve() != jar.resolve():
        shutil.copy2(jar, target)
    manifest = jar_dir / "_manifest.json"
    payload = {"version": version, "jar": target.name, "sha256": sha256_of(target)}
    manifest.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--jar", type=Path, default=None)
    parser.add_argument("--version", default=None)
    parser.add_argument("--jar-dir", type=Path, default=JAR_DIR)
    args = parser.parse_args(argv)
    jar = args.jar or newest_jar(ENGINE_LIBS)
    if jar is None or not jar.is_file():
        print(
            "bundle_jar: no engine JAR found; run `./gradlew shadowJar` in engine/ first",
            file=sys.stderr,
        )
        return 1
    version = args.version or version_from_name(jar)
    if version is None:
        print(
            f"bundle_jar: cannot parse a version from {jar.name}; pass --version",
            file=sys.stderr,
        )
        return 1
    manifest = bundle(jar, version, args.jar_dir)
    print(f"bundled {jar.name} (version {version}) -> {manifest.parent}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
