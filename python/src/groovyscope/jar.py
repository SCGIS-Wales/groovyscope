"""Locate and verify the engine JAR: ``GROOVYSCOPE_JAR`` overrides the copy bundled in the wheel."""

from __future__ import annotations

import hashlib
import json
import os
import zipfile
from collections.abc import Mapping
from pathlib import Path
from typing import Any, TypedDict

from groovyscope import daemon

JAR_DIR = Path(__file__).resolve().parent / "_jar"
MANIFEST_NAME = "_manifest.json"
VERSION_RESOURCE = "org/groovyscope/engine/engine-version.properties"
JAR_ENV = "GROOVYSCOPE_JAR"
_CHUNK = 1 << 20


class JarInfo(TypedDict):
    """Where the engine JAR is and whether it matches the manifest."""

    path: str | None
    source: str
    version: str | None
    codenarc: str | None
    expected_sha256: str | None
    actual_sha256: str | None
    verified: bool | None
    error: str | None


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(_CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_manifest(jar_dir: Path = JAR_DIR) -> dict[str, Any] | None:
    """The bundle manifest (``version``, ``jar``, ``sha256``) or None when nothing is bundled."""
    manifest_path = jar_dir / MANIFEST_NAME
    if not manifest_path.is_file():
        return None
    loaded: dict[str, Any] = json.loads(manifest_path.read_text(encoding="utf-8"))
    return loaded


def write_manifest(jar_dir: Path, *, version: str, jar_name: str, sha256: str, codenarc: str | None = None) -> Path:
    """Write the bundle manifest; ``codenarc`` is the CodeNarc release the engine build embedded, when known."""
    manifest_path = jar_dir / MANIFEST_NAME
    payload: dict[str, str] = {"version": version, "jar": jar_name, "sha256": sha256}
    if codenarc:
        payload["codenarc"] = codenarc
    manifest_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest_path


def embedded_versions(jar_path: Path) -> dict[str, str]:
    """The versions the engine build expanded into the JAR (``version``, ``codenarc``); empty when unreadable.

    The CodeNarc version is chosen by the engine build (``engine/gradle/libs.versions.toml``, see
    ``engine/settings.gradle.kts``), so it is read from the JAR instead of being written anywhere else by hand.
    """
    try:
        with zipfile.ZipFile(jar_path) as archive:
            text = archive.read(VERSION_RESOURCE).decode("utf-8")
    except (OSError, KeyError, zipfile.BadZipFile, UnicodeDecodeError):
        return {}
    versions: dict[str, str] = {}
    for line in text.splitlines():
        key, separator, value = line.partition("=")
        if separator and value.strip() and not value.strip().startswith("${"):
            versions[key.strip()] = value.strip()
    return versions


def locate_jar(
    environ: Mapping[str, str] | None = None, jar_dir: Path = JAR_DIR, fetched_dir: Path | None = None
) -> JarInfo:
    """The JAR to run: ``GROOVYSCOPE_JAR`` (trusted as-is), the bundled JAR, then one from ``groovyscope fetch``.

    Bundled and fetched JARs are checksummed against their manifest every time.
    """
    env = os.environ if environ is None else environ
    override = env.get(JAR_ENV, "")
    if override:
        path = Path(override).expanduser()
        if not path.is_file():
            return _missing(f"{JAR_ENV} points to a missing file: {path}", source=JAR_ENV)
        return {
            "path": str(path),
            "source": JAR_ENV,
            "version": None,
            "codenarc": embedded_versions(path).get("codenarc"),
            "expected_sha256": None,
            "actual_sha256": sha256_of(path),
            "verified": None,
            "error": None,
        }
    manifest = read_manifest(jar_dir)
    if manifest is not None:
        return _verified(jar_dir, manifest, source="bundled")
    fallback = fetched_dir if fetched_dir is not None else daemon.cache_dir(env) / "engine"
    fetched = read_manifest(fallback)
    if fetched is not None:
        return _verified(fallback, fetched, source="fetched")
    return _missing(
        "no engine JAR bundled (development checkout? run scripts/bundle_jar.py, or `groovyscope fetch`)",
        source="bundled",
    )


def _verified(jar_dir: Path, manifest: Mapping[str, Any], *, source: str) -> JarInfo:
    jar_path = jar_dir / str(manifest.get("jar", ""))
    expected = manifest.get("sha256")
    if not jar_path.is_file():
        return _missing(f"manifest names {jar_path.name} but it is not present", source=source)
    actual = sha256_of(jar_path)
    verified = actual == expected
    return {
        "path": str(jar_path),
        "source": source,
        "version": manifest.get("version"),
        "codenarc": manifest.get("codenarc"),
        "expected_sha256": expected,
        "actual_sha256": actual,
        "verified": verified,
        "error": None if verified else "SHA-256 mismatch between the JAR and its manifest",
    }


def _missing(error: str, *, source: str) -> JarInfo:
    return {
        "path": None,
        "source": source,
        "version": None,
        "codenarc": None,
        "expected_sha256": None,
        "actual_sha256": None,
        "verified": None,
        "error": error,
    }
