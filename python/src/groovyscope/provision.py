"""Opt-in JDK provisioning: ``groovyscope jdk install`` or ``jdk.autoProvision: true``.

Off by default, because groovyscope promises to download nothing unless asked. When asked, the JDK comes from
Eclipse Temurin (the Adoptium API) or Amazon Corretto (corretto.aws), its SHA-256 is checked against the checksum
the vendor publishes, and it is unpacked under the cache directory where JDK discovery then finds it.
"""

from __future__ import annotations

import hashlib
import io
import json
import logging
import platform as platform_module
import re
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
CORRETTO_BASE = "https://corretto.aws/downloads"
DEFAULT_FEATURE = 21
VENDORS = ("temurin", "corretto")
_SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+-]{0,100}$")
_CORRETTO_OS = {"mac": "macos", "linux": "linux"}
LOG = logging.getLogger("groovyscope.provision")
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


def version_key(name: str) -> tuple[int, ...]:
    """``jdk-21.0.10+7`` sorts after ``jdk-21.0.9+11``: compare the numbers, not the text."""
    return tuple(int(part) for part in re.findall(r"\d+", name))


def provisioned_homes(install_dir: Path) -> list[Path]:
    """Homes of every JDK under ``install_dir``, newest release first."""
    if not install_dir.is_dir():
        return []
    homes = []
    for child in sorted(install_dir.iterdir(), key=lambda p: version_key(p.name), reverse=True):
        home = home_within(child) if child.is_dir() else None
        if home is not None:
            homes.append(home)
    return homes


def corretto_asset(feature: int, os_name: str, arch: str, downloader: Downloader) -> Asset:
    """The latest Amazon Corretto ``feature`` build and the SHA-256 Amazon publishes for it."""
    name = f"amazon-corretto-{feature}-{arch}-{_CORRETTO_OS[os_name]}-jdk.tar.gz"
    checksum = downloader(f"{CORRETTO_BASE}/latest_sha256/{name}").decode("ascii", "replace").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", checksum):
        msg = f"corretto.aws returned no SHA-256 for {name}"
        raise ValueError(msg)
    return {"url": f"{CORRETTO_BASE}/latest/{name}", "sha256": checksum, "name": name, "release": ""}


def release_from(home: Path, fallback: str) -> str:
    """``corretto-21.0.12.1`` from the unpacked JDK's ``release`` file."""
    try:
        for line in (home / "release").read_text(encoding="utf-8").splitlines():
            if line.startswith("JAVA_VERSION="):
                return f"{fallback}-{line.split('=', 1)[1].strip().strip(chr(34))}"
    except OSError:
        pass
    return fallback


def provision_jdk(
    install_dir: Path,
    *,
    feature: int = DEFAULT_FEATURE,
    downloader: Downloader = default_downloader,
    system: str = sys.platform,
    machine: str = platform_module.machine(),
    vendor: str = "temurin",
) -> ProvisionedJdk:
    """Download the latest ``feature`` release from ``vendor`` for this platform, verify it, unpack it."""
    os_name, arch = adoptium_platform(system, machine)
    if vendor == "corretto":
        asset = corretto_asset(feature, os_name, arch, downloader)
    elif vendor == "temurin":
        asset = pick_asset(json.loads(downloader(assets_url(feature, os_name, arch)).decode("utf-8")))
    else:
        msg = f"unknown JDK vendor {vendor!r}; choose one of {', '.join(VENDORS)}"
        raise ValueError(msg)
    if asset["release"] and not _SAFE_NAME.match(asset["release"]):
        msg = f"refusing an unexpected release name {asset['release']!r}"
        raise ValueError(msg)
    LOG.debug("JDK asset %s (sha256 %s)", asset["url"], asset["sha256"])
    data = downloader(asset["url"])
    actual = hashlib.sha256(data).hexdigest()
    if actual != asset["sha256"]:
        msg = f"SHA-256 mismatch for {asset['name']}: expected {asset['sha256']}, got {actual}"
        raise ValueError(msg)
    install_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=install_dir) as scratch:
        try:
            with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
                archive.extractall(scratch, filter="data")
        except tarfile.TarError as exc:
            msg = f"{asset['name']} could not be unpacked safely: {exc}"
            raise ValueError(msg) from exc
        entries = list(Path(scratch).iterdir())
        unpacked = entries[0] if len(entries) == 1 and entries[0].is_dir() else Path(scratch)
        release = asset["release"] or release_from(home_within(unpacked) or unpacked, vendor)
        if not _SAFE_NAME.match(release):
            release = f"{vendor}-{feature}"
        target = install_dir / release
        if target.exists():
            shutil.rmtree(target)
        shutil.move(str(unpacked), str(target))
    home = home_within(target)
    if home is None:
        shutil.rmtree(target, ignore_errors=True)
        msg = f"{asset['name']} contains no bin/java"
        raise ValueError(msg)
    return {"home": str(home), "version": feature, "release": release}
