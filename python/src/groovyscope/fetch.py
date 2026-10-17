"""``groovyscope fetch``: put a release JAR where the launcher finds it when none is bundled, checksum first.

Nothing here runs implicitly. The user asks for a JAR, names where it comes from (a GitHub release by
default), and the download is refused unless its SHA-256 matches a checksum given on the command line or
found in the ``SHA256SUMS.txt`` published next to it.
"""

from __future__ import annotations

import hashlib
import http.client
import logging
import urllib.error
import urllib.request
from collections.abc import Callable, Mapping
from pathlib import Path
from typing import Any, TypedDict

from groovyscope import __version__, daemon
from groovyscope.jar import write_manifest

RELEASE_BASE = "https://github.com/SCGIS-Wales/groovyscope/releases/download"
SUMS_NAME = "SHA256SUMS.txt"
DOWNLOAD_TIMEOUT_SECONDS = 120
MAX_DOWNLOAD_BYTES = 512 * 1024 * 1024

Downloader = Callable[[str], bytes]
LOG = logging.getLogger("groovyscope.fetch")


class FetchResult(TypedDict):
    """Where the fetched JAR landed and what was verified."""

    path: str
    version: str
    sha256: str
    source: str


def jar_name(version: str) -> str:
    return f"groovyscope-engine-{version}-all.jar"


def default_source(version: str) -> str:
    """The engine JAR attached to the GitHub Release of ``version``."""
    return f"{RELEASE_BASE}/v{version}/{jar_name(version)}"


def fetched_dir(environ: Mapping[str, str]) -> Path:
    """Where ``fetch`` stores the JAR and its manifest (under the cache directory)."""
    return daemon.cache_dir(environ) / "engine"


def https_only_opener() -> urllib.request.OpenerDirector:
    """An opener that follows redirects only to https URLs (urllib would otherwise follow https -> http)."""
    handler = urllib.request.HTTPRedirectHandler()
    follow = handler.redirect_request

    def redirect(request: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> Any:  # noqa: PLR0917 - urllib's signature
        if not newurl.startswith("https://"):
            message = f"refusing a redirect from {request.full_url} to a non-https URL {newurl}"
            raise urllib.error.URLError(message)
        LOG.debug("redirect %s -> %s", request.full_url, newurl)
        return follow(request, fp, code, msg, headers, newurl)

    handler.redirect_request = redirect  # type: ignore[method-assign,assignment]
    return urllib.request.build_opener(handler)


def default_downloader(url: str) -> bytes:
    """Download ``url`` over HTTPS only (redirects included); anything else is refused."""
    if not url.startswith("https://"):
        msg = f"only https:// sources are downloaded, not {url}"
        raise ValueError(msg)
    LOG.debug("downloading %s", url)
    # GitHub's release CDN answers 403 to urllib's default User-Agent, so the request names itself.
    request = urllib.request.Request(url, headers={"User-Agent": f"groovyscope/{__version__}"})  # noqa: S310
    try:
        with https_only_opener().open(request, timeout=DOWNLOAD_TIMEOUT_SECONDS) as response:
            data: bytes = response.read(MAX_DOWNLOAD_BYTES + 1)
    except http.client.HTTPException as exc:  # IncompleteRead and friends are not OSErrors
        msg = f"download of {url} failed: {exc!r}"
        raise ValueError(msg) from exc
    if len(data) > MAX_DOWNLOAD_BYTES:
        msg = f"{url} is larger than {MAX_DOWNLOAD_BYTES} bytes; refusing"
        raise ValueError(msg)
    LOG.debug("downloaded %d bytes from %s", len(data), url)
    return data


def write_atomically(target: Path, data: bytes) -> None:
    """Write bytes via a temporary file and a rename, so a crash never leaves a truncated file behind."""
    temporary = target.with_name(f".{target.name}.part")
    temporary.write_bytes(data)
    temporary.replace(target)


def checksum_from_sums(text: str, name: str) -> str | None:
    """The hex digest for ``name`` in ``sha256sum`` output (``<hex>  <name>``), or None."""
    for line in text.splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[-1].lstrip("*") == name:  # noqa: PLR2004 - digest and name
            return parts[0].lower()
    return None


def _read_source(source: str, name: str, downloader: Downloader) -> tuple[bytes, str | None, str]:
    """``(data, checksum from SHA256SUMS.txt or None, origin)`` for a local file or an https URL."""
    local = Path(source).expanduser()
    if local.is_file():
        sums = local.with_name(SUMS_NAME)
        found = checksum_from_sums(sums.read_text(encoding="utf-8"), name) if sums.is_file() else None
        return (local.read_bytes(), found, str(local))
    data = downloader(source)
    found = None
    base, _, _ = source.rpartition("/")
    if base:
        try:
            found = checksum_from_sums(downloader(f"{base}/{SUMS_NAME}").decode("utf-8"), name)
        except (OSError, ValueError):
            found = None
    return (data, found, source)


def fetch_jar(
    source: str,
    *,
    version: str,
    destination: Path,
    sha256: str | None = None,
    downloader: Downloader = default_downloader,
) -> FetchResult:
    """Copy or download the JAR for ``version`` into ``destination`` after its SHA-256 has been checked.

    ``sha256`` wins when given; otherwise the checksum comes from ``SHA256SUMS.txt`` next to the source.
    Without either the fetch is refused: an unverified engine is never installed.
    """
    name = jar_name(version)
    data, found, origin = _read_source(source, name, downloader)
    expected = (sha256 or found or "").lower()
    if not expected:
        msg = f"no checksum for {name}: pass --sha256 or publish {SUMS_NAME} next to it"
        raise ValueError(msg)
    actual = hashlib.sha256(data).hexdigest()
    LOG.debug("%s: sha256 %s, expected %s (%s)", origin, actual, expected, "given" if sha256 else SUMS_NAME)
    if actual != expected:
        msg = f"SHA-256 mismatch for {origin}: expected {expected}, got {actual}"
        raise ValueError(msg)
    if not sha256:
        LOG.warning(
            "the checksum came from %s next to the JAR, so it detects corruption, not tampering at the source; "
            "pass --sha256 from a channel you trust for that",
            SUMS_NAME,
        )
    destination.mkdir(parents=True, exist_ok=True)
    target = destination / name
    write_atomically(target, data)
    write_manifest(destination, version=version, jar_name=name, sha256=actual)
    return {"path": str(target), "version": version, "sha256": actual, "source": origin}
