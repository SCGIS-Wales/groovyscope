"""Opt-in Temurin JDK provisioning: ``groovyscope jdk install`` or ``jdk.autoProvision: true``.

Off by default, because groovyscope promises to download nothing unless asked. When asked, the JDK comes
from the Adoptium API, its SHA-256 is checked against the checksum the API publishes, and it is unpacked
under the cache directory where JDK discovery then finds it.
"""

from __future__ import annotations

import hashlib
import io
import json
import platform as platform_module
import shutil
import sys
import tarfile
import tempfile
from collections.abc import Mapping
from pathlib import Path
from typing import Any, TypedDict

from groovyscope import daemon
from groovyscope.fetch import Downloader, default_downloader

ADOPTIUM_API = "https://api.adoptium.net/v3"
DEFAULT_FEATURE = 21
_OS_NAMES = {"darwin": "mac", "linux": "linux"}
_ARCH_NAMES = {"arm64": "aarch64", "aarch64": "aarch64", "x86_64": "x64", "amd64": "x64"}


class ProvisionedJdk(TypedDict):
    """A JDK unpacked by groovyscope."""

    home: str
    version: int
    release: str


class Asset(TypedDict):
    url: str
    sha256: str
    name: str
    release: str


def adoptium_platform(system: str = sys.platform, machine: str = platform_module.machine()) -> tuple[str, str]:
    """``(os, architecture)`` as the Adoptium API names them, or ValueError where no Temurin build exists."""
    os_name = _OS_NAMES.get(system)
    arch = _ARCH_NAMES.get(machine.lower())
    if os_name is None or arch is None:
        msg = f"no Temurin build for {system}/{machine}; install a JDK yourself (see docs/install.md)"
        raise ValueError(msg)
    return (os_name, arch)


def assets_url(feature: int, os_name: str, arch: str) -> str:
    return (
        f"{ADOPTIUM_API}/assets/latest/{feature}/hotspot?os={os_name}&architecture={arch}&image_type=jdk&vendor=eclipse"
    )


def pick_asset(payload: Any) -> Asset:
    """The package of the first (latest) asset the API returned."""
    if not isinstance(payload, list) or not payload:
        msg = "Adoptium returned no JDK for this platform"
        raise ValueError(msg)
    first = payload[0]
    package = first["binary"]["package"]
    return {
        "url": str(package["link"]),
        "sha256": str(package["checksum"]).lower(),
        "name": str(package["name"]),
        "release": str(first["release_name"]),
    }


def jdk_dir(environ: Mapping[str, str]) -> Path:
    """Where provisioned JDKs live (under the cache directory)."""
    return daemon.cache_dir(environ) / "jdk"


def home_within(extracted: Path) -> Path | None:
    """The directory holding ``bin/java`` inside an unpacked archive (macOS nests it under Contents/Home)."""
    for candidate in (extracted, extracted / "Contents" / "Home"):
        if (candidate / "bin" / "java").is_file():
            return candidate
    if not extracted.is_dir():
        return None
    for child in sorted(extracted.iterdir()):
        if child.is_dir():
            for candidate in (child, child / "Contents" / "Home"):
                if (candidate / "bin" / "java").is_file():
                    return candidate
    return None


def provisioned_homes(install_dir: Path) -> list[Path]:
    """Homes of every JDK under ``install_dir``, newest release name first."""
    if not install_dir.is_dir():
        return []
    homes = []
    for child in sorted(install_dir.iterdir(), reverse=True):
        home = home_within(child) if child.is_dir() else None
        if home is not None:
            homes.append(home)
    return homes


def provision_jdk(
    install_dir: Path,
    *,
    feature: int = DEFAULT_FEATURE,
    downloader: Downloader = default_downloader,
    system: str = sys.platform,
    machine: str = platform_module.machine(),
) -> ProvisionedJdk:
    """Download the latest Temurin ``feature`` release for this platform, verify it, unpack it."""
    os_name, arch = adoptium_platform(system, machine)
    asset = pick_asset(json.loads(downloader(assets_url(feature, os_name, arch)).decode("utf-8")))
    data = downloader(asset["url"])
    actual = hashlib.sha256(data).hexdigest()
    if actual != asset["sha256"]:
        msg = f"SHA-256 mismatch for {asset['name']}: expected {asset['sha256']}, got {actual}"
        raise ValueError(msg)
    install_dir.mkdir(parents=True, exist_ok=True)
    target = install_dir / asset["release"]
    with tempfile.TemporaryDirectory(dir=install_dir) as scratch:
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
            archive.extractall(scratch, filter="data")
        entries = list(Path(scratch).iterdir())
        unpacked = entries[0] if len(entries) == 1 and entries[0].is_dir() else Path(scratch)
        if target.exists():
            shutil.rmtree(target)
        shutil.move(str(unpacked), str(target))
    home = home_within(target)
    if home is None:
        shutil.rmtree(target, ignore_errors=True)
        msg = f"{asset['name']} contains no bin/java"
        raise ValueError(msg)
    return {"home": str(home), "version": feature, "release": asset["release"]}
