#!/usr/bin/env python3
"""Render ``Formula/groovyscope.rb`` for the SCGIS-Wales Homebrew tap from a version published on PyPI.

The formula installs the PyPI sdist (which bundles the engine JAR) into a virtualenv with uv, depends on
Homebrew's ``openjdk@21`` and wraps the executable so ``JAVA_HOME`` points at that JDK. Usage:

    python3 scripts/homebrew_formula.py --version 0.6.0 > Formula/groovyscope.rb
    python3 scripts/homebrew_formula.py --version 0.6.0 --url <sdist url> --sha256 <hex>   # no PyPI lookup
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.request
from pathlib import Path

PYPI_JSON = "https://pypi.org/pypi/groovyscope/{version}/json"

TEMPLATE = """class Groovyscope < Formula
  include Language::Python::Virtualenv

  desc "Groovy 5/6 code intelligence (LSP and MCP) for AI coding agents such as Kiro CLI"
  homepage "https://github.com/SCGIS-Wales/groovyscope"
  url "{url}"
  sha256 "{sha256}"
  license "Apache-2.0"

  depends_on "uv" => :build
  depends_on "openjdk@21"
  depends_on "python@3.13"

  def install
    venv = virtualenv_create(libexec, "python3.13")
    # The sdist bundles the engine JAR; uv resolves the Python dependencies as binary wheels from PyPI.
    system "uv", "pip", "install", "--python", venv.root/"bin/python", "--no-cache", buildpath
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


def render(url: str, sha256: str) -> str:
    return TEMPLATE.format(url=url, sha256=sha256)


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
    formula = render(url, sha256)
    if args.output:
        Path(args.output).write_text(formula, encoding="utf-8")
    else:
        sys.stdout.write(formula)
    return 0


if __name__ == "__main__":
    sys.exit(main())
