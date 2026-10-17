"""Locate and verify the engine JAR: ``GROOVYSCOPE_JAR`` overrides the copy bundled in the wheel."""

from __future__ import annotations

import hashlib
import json
import os
from collections.abc import Mapping
from pathlib import Path
from typing import Any, TypedDict

JAR_DIR = Path(__file__).resolve().parent / "_jar"
MANIFEST_NAME = "_manifest.json"
JAR_ENV = "GROOVYSCOPE_JAR"
_CHUNK = 1 << 20


class JarInfo(TypedDict):
    """Where the engine JAR is and whether it matches the manifest."""

    path: str | None
    source: str
    version: str | None
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


def write_manifest(jar_dir: Path, *, version: str, jar_name: str, sha256: str) -> Path:
    manifest_path = jar_dir / MANIFEST_NAME
    payload = {"version": version, "jar": jar_name, "sha256": sha256}
    manifest_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest_path


def locate_jar(environ: Mapping[str, str] | None = None, jar_dir: Path = JAR_DIR) -> JarInfo:
    """Resolve the JAR to run. An explicit ``GROOVYSCOPE_JAR`` is trusted as-is; the bundled JAR is checksummed."""
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
            "expected_sha256": None,
            "actual_sha256": sha256_of(path),
            "verified": None,
            "error": None,
        }
    manifest = read_manifest(jar_dir)
    if manifest is None:
        return _missing("no engine JAR bundled (development checkout? run scripts/bundle_jar.py)", source="bundled")
    jar_path = jar_dir / str(manifest.get("jar", ""))
    expected = manifest.get("sha256")
    if not jar_path.is_file():
        return _missing(f"manifest names {jar_path.name} but it is not present", source="bundled")
    actual = sha256_of(jar_path)
    verified = actual == expected
    return {
        "path": str(jar_path),
        "source": "bundled",
        "version": manifest.get("version"),
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
        "expected_sha256": None,
        "actual_sha256": None,
        "verified": None,
        "error": error,
    }
