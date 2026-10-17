"""``groovyscope fetch``: checksum-verified JAR installs, from a URL or a local file."""

from __future__ import annotations

import hashlib
from pathlib import Path

import pytest

from groovyscope import fetch
from groovyscope.jar import locate_jar, read_manifest

JAR = b"PK\x03\x04 engine"
DIGEST = hashlib.sha256(JAR).hexdigest()


def fake_web(files: dict[str, bytes]) -> fetch.Downloader:
    def download(url: str) -> bytes:
        if url not in files:
            msg = f"404 {url}"
            raise OSError(msg)
        return files[url]

    return download


def test_names_and_default_source() -> None:
    assert fetch.jar_name("0.6.0") == "groovyscope-engine-0.6.0-all.jar"
    assert fetch.default_source("0.6.0").endswith("/v0.6.0/groovyscope-engine-0.6.0-all.jar")
    assert fetch.fetched_dir({"GROOVYSCOPE_CACHE_DIR": "/c"}) == Path("/c/engine")


def test_checksum_from_sums() -> None:
    text = f"{DIGEST}  groovyscope-engine-0.6.0-all.jar\nabc *other.jar\n\nbroken\n"
    assert fetch.checksum_from_sums(text, "groovyscope-engine-0.6.0-all.jar") == DIGEST
    assert fetch.checksum_from_sums(text, "other.jar") == "abc"
    assert fetch.checksum_from_sums(text, "missing.jar") is None


def test_fetch_from_url_with_published_sums(tmp_path: Path) -> None:
    base = "https://example.test/v0.6.0"
    web = fake_web(
        {
            f"{base}/groovyscope-engine-0.6.0-all.jar": JAR,
            f"{base}/SHA256SUMS.txt": f"{DIGEST}  groovyscope-engine-0.6.0-all.jar\n".encode(),
        }
    )
    result = fetch.fetch_jar(
        f"{base}/groovyscope-engine-0.6.0-all.jar", version="0.6.0", destination=tmp_path / "engine", downloader=web
    )
    assert result["sha256"] == DIGEST and result["source"].startswith("https://")
    assert Path(result["path"]).read_bytes() == JAR
    assert read_manifest(tmp_path / "engine") == {
        "jar": "groovyscope-engine-0.6.0-all.jar",
        "sha256": DIGEST,
        "version": "0.6.0",
    }
    located = locate_jar({}, tmp_path / "nothing-bundled", fetched_dir=tmp_path / "engine")
    assert located["source"] == "fetched" and located["verified"] is True


def test_fetch_requires_a_checksum_and_rejects_mismatches(tmp_path: Path) -> None:
    web = fake_web({"https://example.test/x.jar": JAR})
    with pytest.raises(ValueError, match="no checksum"):
        fetch.fetch_jar("https://example.test/x.jar", version="0.6.0", destination=tmp_path, downloader=web)
    with pytest.raises(ValueError, match="SHA-256 mismatch"):
        fetch.fetch_jar(
            "https://example.test/x.jar", version="0.6.0", destination=tmp_path, sha256="00" * 32, downloader=web
        )
    assert not (tmp_path / "groovyscope-engine-0.6.0-all.jar").exists()


def test_fetch_from_a_local_file(tmp_path: Path) -> None:
    source = tmp_path / "downloads" / "groovyscope-engine-0.6.0-all.jar"
    source.parent.mkdir()
    source.write_bytes(JAR)
    with pytest.raises(ValueError, match="no checksum"):
        fetch.fetch_jar(str(source), version="0.6.0", destination=tmp_path / "engine", downloader=fake_web({}))
    (source.parent / "SHA256SUMS.txt").write_text(f"{DIGEST}  {source.name}\n", encoding="utf-8")
    result = fetch.fetch_jar(str(source), version="0.6.0", destination=tmp_path / "engine", downloader=fake_web({}))
    assert result["source"] == str(source)
    explicit = fetch.fetch_jar(
        str(source), version="0.6.0", destination=tmp_path / "e2", sha256=DIGEST.upper(), downloader=fake_web({})
    )
    assert explicit["sha256"] == DIGEST


def test_default_downloader_refuses_non_https() -> None:
    with pytest.raises(ValueError, match="only https"):
        fetch.default_downloader("http://example.test/x.jar")
    with pytest.raises(ValueError, match="only https"):
        fetch.default_downloader("file:///etc/passwd")
