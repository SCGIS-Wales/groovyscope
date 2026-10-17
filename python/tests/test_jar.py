from __future__ import annotations

import hashlib
from pathlib import Path

from groovyscope.jar import JAR_ENV, locate_jar, read_manifest, sha256_of, write_manifest


def test_sha256_of(tmp_path: Path) -> None:
    target = tmp_path / "blob"
    target.write_bytes(b"groovy" * 1000)
    assert sha256_of(target) == hashlib.sha256(b"groovy" * 1000).hexdigest()


def test_read_manifest_absent(tmp_path: Path) -> None:
    assert read_manifest(tmp_path) is None


def test_write_and_read_manifest(tmp_path: Path) -> None:
    path = write_manifest(tmp_path, version="1.2.3", jar_name="x.jar", sha256="abc")
    assert path.name == "_manifest.json"
    assert read_manifest(tmp_path) == {"version": "1.2.3", "jar": "x.jar", "sha256": "abc"}


def test_locate_bundled_jar_verified(fake_jar_dir: Path) -> None:
    info = locate_jar({}, fake_jar_dir)
    assert info["source"] == "bundled"
    assert info["verified"] is True
    assert info["error"] is None
    assert info["version"] == "0.1.0"
    assert info["path"] is not None and info["path"].endswith("-all.jar")


def test_locate_bundled_jar_checksum_mismatch(fake_jar_dir: Path) -> None:
    next(fake_jar_dir.glob("*.jar")).write_bytes(b"tampered")
    info = locate_jar({}, fake_jar_dir)
    assert info["verified"] is False
    assert info["error"] is not None and "SHA-256" in info["error"]


def test_locate_bundled_jar_missing_file(fake_jar_dir: Path) -> None:
    next(fake_jar_dir.glob("*.jar")).unlink()
    info = locate_jar({}, fake_jar_dir)
    assert info["path"] is None
    assert info["error"] is not None and "not present" in info["error"]


def test_locate_jar_nothing_bundled(tmp_path: Path) -> None:
    info = locate_jar({}, tmp_path)
    assert info["path"] is None
    assert info["error"] is not None and "no engine JAR bundled" in info["error"]


def test_locate_jar_env_override(tmp_path: Path) -> None:
    jar = tmp_path / "custom.jar"
    jar.write_bytes(b"x")
    info = locate_jar({JAR_ENV: str(jar)}, tmp_path)
    assert info["source"] == JAR_ENV
    assert info["path"] == str(jar)
    assert info["verified"] is None
    assert info["actual_sha256"] == sha256_of(jar)
    missing = locate_jar({JAR_ENV: str(tmp_path / "nope.jar")}, tmp_path)
    assert missing["error"] is not None and "missing file" in missing["error"]
