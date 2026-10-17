"""Temurin provisioning against a fake Adoptium API and a fake JDK tarball."""

from __future__ import annotations

import hashlib
import io
import json
import tarfile
from pathlib import Path

import pytest

from groovyscope import provision
from groovyscope.fetch import Downloader
from groovyscope.java import discover_jdk
from tests.conftest import make_fake_jdk


def jdk_tarball(tmp_path: Path, *, mac_layout: bool) -> bytes:
    """A gzipped tar of a fake JDK laid out like Temurin's (macOS nests the home under Contents/Home)."""
    root = tmp_path / "staging" / "jdk-21.0.9+10"
    home = root / "Contents" / "Home" if mac_layout else root
    make_fake_jdk(home.parent, "21.0.9").rename(home)
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
        archive.add(root, arcname=root.name)
    return buffer.getvalue()


def fake_adoptium(tarball: bytes, *, checksum: str | None = None) -> tuple[Downloader, list[str]]:
    digest = checksum or hashlib.sha256(tarball).hexdigest()
    payload = [
        {
            "release_name": "jdk-21.0.9+10",
            "binary": {
                "package": {"link": "https://example.test/jdk.tar.gz", "checksum": digest, "name": "jdk.tar.gz"}
            },
        }
    ]
    seen: list[str] = []

    def download(url: str) -> bytes:
        seen.append(url)
        if url.startswith(provision.ADOPTIUM_API):
            return json.dumps(payload).encode("utf-8")
        if url == "https://example.test/jdk.tar.gz":
            return tarball
        msg = f"404 {url}"
        raise OSError(msg)

    return download, seen


def test_adoptium_platform_names() -> None:
    assert provision.adoptium_platform("darwin", "arm64") == ("mac", "aarch64")
    assert provision.adoptium_platform("linux", "x86_64") == ("linux", "x64")
    assert provision.adoptium_platform("linux", "AMD64") == ("linux", "x64")
    with pytest.raises(ValueError, match="no Temurin build"):
        provision.adoptium_platform("win32", "x86_64")
    assert "os=mac&architecture=aarch64" in provision.assets_url(21, "mac", "aarch64")


def test_pick_asset_requires_a_result() -> None:
    with pytest.raises(ValueError, match="no JDK"):
        provision.pick_asset([])
    with pytest.raises(ValueError, match="no JDK"):
        provision.pick_asset({"error": "x"})


@pytest.mark.parametrize("mac_layout", [True, False])
def test_provision_unpacks_verifies_and_is_discovered(tmp_path: Path, mac_layout: bool) -> None:
    tarball = jdk_tarball(tmp_path, mac_layout=mac_layout)
    download, seen = fake_adoptium(tarball)
    install_dir = tmp_path / "cache" / "jdk"
    installed = provision.provision_jdk(install_dir, feature=21, downloader=download, system="linux", machine="x86_64")
    assert installed["release"] == "jdk-21.0.9+10" and installed["version"] == 21
    home = Path(installed["home"])
    assert (home / "bin" / "java").is_file()
    assert install_dir in home.parents and home.relative_to(install_dir).parts[0] == "jdk-21.0.9+10"
    assert seen[0].startswith(provision.ADOPTIUM_API) and seen[1] == "https://example.test/jdk.tar.gz"
    assert provision.provisioned_homes(install_dir) == [home]
    found = discover_jdk(
        None, {"PATH": "/nonexistent"}, platform="linux", which=lambda _n: None, provisioned=[home], scan=False
    )
    assert found is not None and found["source"] == "provisioned" and found["version"] == 21
    # Installing again replaces the previous unpack instead of failing.
    again = provision.provision_jdk(install_dir, feature=21, downloader=download, system="linux", machine="x86_64")
    assert again["home"] == installed["home"]


def test_provision_rejects_a_bad_checksum(tmp_path: Path) -> None:
    tarball = jdk_tarball(tmp_path, mac_layout=False)
    download, _ = fake_adoptium(tarball, checksum="00" * 32)
    with pytest.raises(ValueError, match="SHA-256 mismatch"):
        provision.provision_jdk(tmp_path / "jdk", feature=21, downloader=download, system="linux", machine="x86_64")
    assert provision.provisioned_homes(tmp_path / "jdk") == []


def test_provision_rejects_an_archive_without_java(tmp_path: Path) -> None:
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
        readme = tmp_path / "README"
        readme.write_text("nothing here", encoding="utf-8")
        archive.add(readme, arcname="jdk-21.0.9+10/README")
    download, _ = fake_adoptium(buffer.getvalue())
    with pytest.raises(ValueError, match="no bin/java"):
        provision.provision_jdk(tmp_path / "jdk", feature=21, downloader=download, system="linux", machine="x86_64")
    assert provision.provisioned_homes(tmp_path / "jdk") == []
    assert provision.provisioned_homes(tmp_path / "absent") == []


def test_corretto_is_verified_and_named_from_its_release_file(tmp_path: Path) -> None:
    staging = tmp_path / "staging" / "amazon-corretto-21.jdk"
    home = staging / "Contents" / "Home"
    make_fake_jdk(home.parent, "21.0.12.1").rename(home)
    (home / "release").write_text('JAVA_VERSION="21.0.12.1"\n', encoding="utf-8")
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
        archive.add(staging, arcname=staging.name)
    tarball = buffer.getvalue()
    name = "amazon-corretto-21-aarch64-macos-jdk.tar.gz"
    files = {
        f"{provision.CORRETTO_BASE}/latest_sha256/{name}": hashlib.sha256(tarball).hexdigest().encode() + b"\n",
        f"{provision.CORRETTO_BASE}/latest/{name}": tarball,
    }
    installed = provision.provision_jdk(
        tmp_path / "jdk", feature=21, downloader=files.__getitem__, system="darwin", machine="arm64", vendor="corretto"
    )
    assert installed["release"] == "corretto-21.0.12.1"
    assert installed["home"].endswith("corretto-21.0.12.1/Contents/Home")
    files[f"{provision.CORRETTO_BASE}/latest_sha256/{name}"] = b"<html>not found</html>"
    with pytest.raises(ValueError, match="no SHA-256"):
        provision.provision_jdk(
            tmp_path / "jdk2", downloader=files.__getitem__, system="darwin", machine="arm64", vendor="corretto"
        )
    with pytest.raises(ValueError, match="unknown JDK vendor"):
        provision.provision_jdk(
            tmp_path / "jdk3", downloader=files.__getitem__, system="darwin", machine="arm64", vendor="other"
        )


def test_release_names_are_validated_and_versions_sorted_numerically(tmp_path: Path) -> None:
    tarball = jdk_tarball(tmp_path, mac_layout=False)
    download, _ = fake_adoptium(tarball)

    def evil(url: str) -> bytes:
        data = download(url)
        return data.replace(b"jdk-21.0.9+10", b"../../escape") if url.startswith(provision.ADOPTIUM_API) else data

    with pytest.raises(ValueError, match="unexpected release name"):
        provision.provision_jdk(tmp_path / "jdk", downloader=evil, system="linux", machine="x86_64")
    with pytest.raises(ValueError, match="could not be unpacked"):
        bad, _ = fake_adoptium(b"not a tarball")
        provision.provision_jdk(tmp_path / "jdk", downloader=bad, system="linux", machine="x86_64")
    for name in ("jdk-21.0.9+11", "jdk-21.0.10+7"):
        make_fake_jdk(tmp_path / "many", "21").rename(tmp_path / "many" / name)
    assert [p.name for p in provision.provisioned_homes(tmp_path / "many")] == ["jdk-21.0.10+7", "jdk-21.0.9+11"]
    assert provision.release_from(tmp_path / "nowhere", "corretto") == "corretto"
