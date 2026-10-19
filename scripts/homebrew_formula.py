#!/usr/bin/env python3
"""Render ``Formula/groovyscope.rb`` for the SCGIS-Wales Homebrew tap from a version published on PyPI.

The formula installs the PyPI sdist (which bundles the engine JAR) into a virtualenv with uv, depends on
Homebrew's ``openjdk@21`` and wraps the executable so ``JAVA_HOME`` points at that JDK. The Python dependencies are
installed from the release's ``groovyscope-requirements.txt`` (exported from ``uv.lock`` with every hash, attested by
the publish workflow) with ``--require-hashes``, and the package itself with ``--no-deps``, so nothing unhashed is
fetched; a release from before that file gets the unhashed install with a warning. Usage:

    python3 scripts/homebrew_formula.py --version 0.6.0 > Formula/groovyscope.rb
    python3 scripts/homebrew_formula.py --version 0.6.0 --url <sdist url> --sha256 <hex>   # no PyPI lookup
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

PYPI_JSON = "https://pypi.org/pypi/groovyscope/{version}/json"
REQUIREMENTS_ASSET = (
    "https://github.com/SCGIS-Wales/groovyscope/releases/download/v{version}/groovyscope-requirements.txt"
)
# Homebrew spells an SPDX "A AND B" as all_of; the same licences as the wheel (THIRD-PARTY-NOTICES.md).
LICENSES = ("Apache-2.0", "EPL-2.0", "EPL-1.0", "BSD-3-Clause", "BSD-2-Clause", "MIT")

TEMPLATE = """class Groovyscope < Formula
  include Language::Python::Virtualenv

  desc "Groovy 5/6 code intelligence (LSP and MCP) for AI coding agents such as Kiro CLI"
  homepage "https://github.com/SCGIS-Wales/groovyscope"
  url "{url}"
  sha256 "{sha256}"
  license all_of: [{license}]

  depends_on "uv" => :build
  depends_on "openjdk@21"
  depends_on "python@3.13"

  def install
    venv = virtualenv_create(libexec, "python3.13")
{install}
    java_home = Formula["openjdk@21"].opt_libexec
    java_home = java_home/"openjdk.jdk/Contents/Home" if OS.mac?
    (bin/"groovyscope").write_env_script venv.root/"bin/groovyscope", JAVA_HOME: java_home
  end

  test do
    assert_match version.to_s, shell_output("#{{bin}}/groovyscope version")
    report = shell_output("#{{bin}}/groovyscope doctor")
    assert_match "jdk", report
    assert_match "checksum verified", report
  end
end
"""


def sdist_from_pypi(version: str, wait_seconds: int) -> tuple[str, str]:
    """``(url, sha256)`` of the sdist for ``version``, polling PyPI until it is listed or the wait runs out."""
    deadline = time.monotonic() + wait_seconds
    while True:
        try:
            with urllib.request.urlopen(PYPI_JSON.format(version=version), timeout=30) as response:  # noqa: S310
                payload = json.load(response)
            for item in payload.get("urls", []):
                if item.get("packagetype") == "sdist":
                    return (str(item["url"]), str(item["digests"]["sha256"]))
            message = f"PyPI lists {version} without an sdist"
        except OSError as exc:
            message = f"PyPI does not serve {version} yet ({exc})"
        if time.monotonic() >= deadline:
            raise SystemExit(message)
        time.sleep(15)


UNHASHED_INSTALL = """\
    # The sdist bundles the engine JAR; uv resolves the Python dependencies as binary wheels from PyPI.
    system "uv", "pip", "install", "--python", venv.root/"bin/python", "--no-cache", buildpath"""

HASHED_INSTALL = """\
    # The Python dependencies, pinned with their hashes (exported from uv.lock at the release); the package
    # without dependencies, so nothing unhashed is fetched.
    (buildpath/"requirements.txt").write <<~'REQUIREMENTS'
{requirements}
    REQUIREMENTS
    system "uv", "pip", "install", "--python", venv.root/"bin/python", "--no-cache", "--require-hashes",
           "-r", buildpath/"requirements.txt"
    system "uv", "pip", "install", "--python", venv.root/"bin/python", "--no-cache", "--no-deps", buildpath"""


def unhashed(requirements: str) -> list[str]:
    """The requirements in ``requirements`` (continuation lines joined, comments dropped) that carry no hash."""
    joined = requirements.replace("\\\n", " ")
    lines = [
        line.split("#", 1)[0].strip() if not line.lstrip().startswith("--hash") else line
        for line in joined.splitlines()
    ]
    return [line.split()[0] for line in lines if line and not line.startswith("-") and "--hash=" not in line]


def render(url: str, sha256: str, requirements: str | None = None) -> str:
    """The formula; with ``requirements`` (hashed, as ``uv export`` writes them) the dependencies install by hash."""
    if requirements is None:
        install = UNHASHED_INSTALL
    else:
        missing = unhashed(requirements)
        if missing:
            msg = f"requirements without a hash: {', '.join(missing)}"
            raise ValueError(msg)
        body = "\n".join(f"      {line}" if line else "" for line in requirements.strip().splitlines())
        install = HASHED_INSTALL.format(requirements=body)
    licenses = ", ".join(f'"{name}"' for name in LICENSES)
    return TEMPLATE.format(url=url, sha256=sha256, license=licenses, install=install)


def requirements_from_release(version: str) -> str | None:
    """The release's hashed requirements, or None when the release has none (published before they existed)."""
    try:
        with urllib.request.urlopen(REQUIREMENTS_ASSET.format(version=version), timeout=30) as response:  # noqa: S310
            return str(response.read().decode("utf-8"))
    except (urllib.error.HTTPError, OSError):
        return None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--version", required=True, help="released version, for example 0.6.0")
    parser.add_argument("--url", help="sdist URL (skips the PyPI lookup when given with --sha256)")
    parser.add_argument("--sha256", help="sdist SHA-256")
    parser.add_argument("--wait", type=int, default=0, help="seconds to wait for PyPI to list the version")
    parser.add_argument("--output", help="write the formula here instead of stdout")
    args = parser.parse_args(argv)
    if args.url and args.sha256:
        url, sha256 = args.url, args.sha256
    else:
        url, sha256 = sdist_from_pypi(args.version, args.wait)
    requirements = requirements_from_release(args.version)
    if requirements is None:
        print(
            f"homebrew_formula: no hashed requirements for {args.version}; dependencies install unhashed",
            file=sys.stderr,
        )
    formula = render(url, sha256, requirements)
    if args.output:
        Path(args.output).write_text(formula, encoding="utf-8")
    else:
        sys.stdout.write(formula)
    return 0


if __name__ == "__main__":
    sys.exit(main())
