"""`groovyscope init`: a starting `.groovyscope.yaml` from what the workspace's build already says.

Detected settings are only added where the existing file says nothing; a user's own choices always win. Nothing
runs: the build files are read as text.
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from pathlib import Path
from typing import Any

import yaml

from groovyscope.config import CONFIG_FILENAME, deep_merge

ROOT_CANDIDATES = (
    "src/main/groovy",
    "src/test/groovy",
    "src/main/java",
    "src/test/java",
    "src/integrationTest/groovy",
    "src/testFixtures/groovy",
    "vars",
    "src",
)
BUILD_FILES = ("build.gradle", "build.gradle.kts", "pom.xml", "gradle/libs.versions.toml")
# One plain pattern per build format: Gradle coordinates (Groovy or Kotlin DSL), Maven XML, a version catalog.
_GROOVY_VERSIONS = (
    re.compile(r"org\.(?:apache|codehaus)\.groovy:groovy(?:-all)?:(?P<version>\d+\.\d+)"),
    re.compile(
        r"<groupId>org\.(?:apache|codehaus)\.groovy</groupId>\s*<artifactId>groovy(?:-all)?</artifactId>"
        r"\s*<version>(?P<version>\d+\.\d+)"
    ),
    re.compile(r"^\s*groovy\s*=\s*[\"'](?P<version>\d+\.\d+)", re.MULTILINE),
)


def detect_roots(root: Path) -> list[str]:
    """Source roots that exist, conventional layout first; ``src`` only when nothing more specific exists."""
    found = [relative for relative in ROOT_CANDIDATES[:-1] if (root / relative).is_dir()]
    if not found and (root / "src").is_dir():
        found = ["src"]
    return found


def detect_groovy_version(root: Path) -> str | None:
    """``5.1`` / ``6.0``: the major.minor of the Groovy dependency a build file declares, when one does."""
    for name in BUILD_FILES:
        try:
            text = (root / name).read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for pattern in _GROOVY_VERSIONS:
            match = pattern.search(text)
            if match:
                return match.group("version")
    return None


def detected(root: Path) -> dict[str, Any]:
    """The settings the workspace suggests."""
    settings: dict[str, Any] = {}
    roots = detect_roots(root)
    if roots:
        settings["workspace"] = {"sourceRoots": roots}
    version = detect_groovy_version(root)
    if version:
        major = int(version.split(".")[0])
        settings["groovy"] = {"version": version, "compat": [] if major >= 6 else [version]}  # noqa: PLR2004
    return settings


def merged_config(existing: Mapping[str, Any], suggestion: Mapping[str, Any]) -> dict[str, Any]:
    """The suggestion under the existing settings: nothing the user wrote is replaced."""
    return deep_merge(suggestion, existing)


def render(config: Mapping[str, Any]) -> str:
    header = "# groovyscope settings for this workspace; defaults: `groovyscope config validate --show`\n"
    return header + (yaml.safe_dump(dict(config), sort_keys=False, default_flow_style=False) if config else "")


def plan(root: Path) -> tuple[Path, str | None, str]:
    """``(target, current text or None, new text)``."""
    target = root / CONFIG_FILENAME
    current = target.read_text(encoding="utf-8") if target.is_file() else None
    existing = yaml.safe_load(current) if current else {}
    if not isinstance(existing, Mapping):
        msg = f"{target} must contain a mapping"
        raise ValueError(msg)
    return (target, current, render(merged_config(existing, detected(root))))
