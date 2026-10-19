#!/usr/bin/env python3
"""Copy the shaded engine JAR into the Python package and write its manifest (version, file name, SHA-256).

Usage: python scripts/bundle_jar.py [--jar PATH] [--version X.Y.Z] [--jar-dir DIR] [--repo DIR]

Without --jar the version comes from python/pyproject.toml; engine/gradle.properties must name the same one, and the
JAR bundled is engine/build/libs/groovyscope-engine-<version>-all.jar (never merely the newest file there). A JAR
whose embedded version (engine-version.properties) disagrees is refused, so a wheel never ships a stale engine.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
import tomllib
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
JAR_DIR = REPO / "python" / "src" / "groovyscope" / "_jar"
NAME_RE = re.compile(r"groovyscope-engine-(?P<version>.+)-all\.jar$")
GRADLE_VERSION_RE = re.compile(r"(?m)^version=(?P<version>\S+)\s*$")
VERSION_RESOURCE = "org/groovyscope/engine/engine-version.properties"


def newest_jar(libs: Path) -> Path | None:
    """The newest engine JAR in ``libs`` (kept for callers that name no version)."""
    candidates = sorted(libs.glob("groovyscope-engine-*-all.jar"), key=lambda p: p.stat().st_mtime)
    return candidates[-1] if candidates else None


def version_from_name(jar: Path) -> str | None:
    match = NAME_RE.search(jar.name)
    return match.group("version") if match else None


def project_versions(repo: Path) -> dict[str, str | None]:
    """The version each build file of the repository declares (``None`` where it declares none)."""
    pyproject = repo / "python" / "pyproject.toml"
    gradle = repo / "engine" / "gradle.properties"
    python_version = tomllib.loads(pyproject.read_text(encoding="utf-8")).get("project", {}).get("version")
    match = GRADLE_VERSION_RE.search(gradle.read_text(encoding="utf-8")) if gradle.is_file() else None
    return {
        str(pyproject.relative_to(repo)): python_version,
        str(gradle.relative_to(repo)): match.group("version") if match else None,
    }


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def embedded_versions(jar: Path) -> dict[str, str]:
    """The versions Gradle expanded into the JAR, notably the CodeNarc release it embeds."""
    try:
        with zipfile.ZipFile(jar) as archive:
            text = archive.read(VERSION_RESOURCE).decode("utf-8")
    except (OSError, KeyError, zipfile.BadZipFile, UnicodeDecodeError):
        return {}
    versions: dict[str, str] = {}
    for line in text.splitlines():
        key, separator, value = line.partition("=")
        if separator and value.strip() and not value.strip().startswith("${"):
            versions[key.strip()] = value.strip()
    return versions


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
    codenarc = embedded_versions(target).get("codenarc")
    if codenarc:
        payload["codenarc"] = codenarc
    manifest.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest


def choose(args: argparse.Namespace) -> tuple[Path, str] | str:
    """The JAR and version to bundle, or the reason there is none."""
    if args.jar is not None:
        if not args.jar.is_file():
            return f"no engine JAR at {args.jar}; run `./gradlew shadowJar` in engine/ first"
        version = args.version or version_from_name(args.jar)
        if version is None:
            return f"cannot parse a version from {args.jar.name}; pass --version"
        return (args.jar, version)
    declared = project_versions(args.repo)
    versions = set(declared.values())
    if len(versions) != 1 or None in versions:
        listed = ", ".join(f"{name}: {value}" for name, value in declared.items())
        return f"the build files disagree on the version ({listed})"
    version = str(versions.pop())
    jar = args.repo / "engine" / "build" / "libs" / f"groovyscope-engine-{version}-all.jar"
    if not jar.is_file():
        return f"no engine JAR for version {version} at {jar}; run `./gradlew shadowJar` in engine/ first"
    return (jar, version)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jar", type=Path, default=None)
    parser.add_argument("--version", default=None)
    parser.add_argument("--jar-dir", type=Path, default=JAR_DIR)
    parser.add_argument("--repo", type=Path, default=REPO)
    args = parser.parse_args(argv)
    chosen = choose(args)
    if isinstance(chosen, str):
        print(f"bundle_jar: {chosen}", file=sys.stderr)
        return 1
    jar, version = chosen
    embedded = embedded_versions(jar).get("version")
    if embedded is not None and embedded != version:
        print(f"bundle_jar: {jar.name} was built as version {embedded}, not {version}; rebuild it", file=sys.stderr)
        return 1
    manifest = bundle(jar, version, args.jar_dir)
    codenarc = json.loads(manifest.read_text(encoding="utf-8")).get("codenarc", "unknown")
    print(f"bundled {jar.name} (version {version}, CodeNarc {codenarc}) -> {manifest.parent}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
