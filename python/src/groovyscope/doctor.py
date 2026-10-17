"""Environment checks behind ``groovyscope doctor``: pure report building, no printing."""

from __future__ import annotations

import sys
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from groovyscope import __version__, daemon
from groovyscope.config import load_config
from groovyscope.jar import locate_jar
from groovyscope.java import RECOMMENDED_JDK, JdkInfo, discover_jdk, jdk_status
from groovyscope.kiro import present_files
from groovyscope.provision import jdk_dir, provisioned_homes

MINIMUM_PYTHON = (3, 12)

Check = tuple[str, str, str]  # (level, name, message); level in ok | warn | fail | info


def python_check(version_info: tuple[int, ...] = tuple(sys.version_info[:3])) -> Check:
    rendered = ".".join(str(part) for part in version_info[:3])
    if version_info[:2] < MINIMUM_PYTHON:
        return ("fail", "python", f"{rendered} is below {MINIMUM_PYTHON[0]}.{MINIMUM_PYTHON[1]}")
    return ("ok", "python", rendered)


def config_check(
    root: Path, config_path: Path | None, environ: Mapping[str, str]
) -> tuple[Check, dict[str, Any] | None]:
    try:
        config = load_config(root, config_path, environ)
    except (ValueError, OSError) as exc:
        return (("fail", "config", str(exc).replace("\n", " ")), None)
    source = str(config_path) if config_path else "defaults + .groovyscope.yaml when present"
    return (("ok", "config", source), config)


def jdk_check(config: Mapping[str, Any] | None, environ: Mapping[str, str]) -> tuple[Check, JdkInfo | None]:
    jdk_section = (config or {}).get("jdk", {})
    configured = jdk_section.get("home")
    minimum = int(jdk_section.get("minimum", 17))
    provisioned = provisioned_homes(jdk_dir(environ))
    info = discover_jdk(configured, environ, minimum=minimum, provisioned=provisioned)
    level, message = jdk_status(info, minimum, RECOMMENDED_JDK)
    if level == "fail" and info is None and jdk_section.get("autoProvision"):
        message += "; jdk.autoProvision is on, so the next `groovyscope lsp` will download Temurin"
    return ((level, "jdk", message), info)


def jar_check(environ: Mapping[str, str]) -> Check:
    info = locate_jar(environ)
    if info["error"]:
        return ("fail", "engine jar", info["error"])
    if info["source"] in {"bundled", "fetched"}:
        return ("ok", "engine jar", f"{info['path']} (version {info['version']}, {info['source']}, checksum verified)")
    return ("warn", "engine jar", f"{info['path']} from GROOVYSCOPE_JAR (not checksum verified)")


def kiro_check(root: Path) -> Check:
    presence = present_files(root)
    missing = [name for name, present in presence.items() if not present]
    if not missing:
        return ("ok", "kiro files", "all four present")
    if len(missing) == len(presence):
        return ("info", "kiro files", "none found; run `groovyscope kiro init` in the workspace")
    return ("warn", "kiro files", "missing: " + ", ".join(missing))


def daemon_check(root: Path, environ: Mapping[str, str]) -> Check:
    details = daemon.info(root, environ)
    if details["running"]:
        engine = (details["status"] or {}).get("engine", "?")
        return ("ok", "daemon", f"running on {details['socket']} (engine {engine})")
    return ("info", "daemon", f"not running; starts with the first `groovyscope lsp` session (log: {details['log']})")


def build_report(root: Path, config_path: Path | None, environ: Mapping[str, str]) -> list[Check]:
    config_result, config = config_check(root, config_path, environ)
    jdk_result, _ = jdk_check(config, environ)
    return [
        ("ok", "groovyscope", __version__),
        python_check(),
        config_result,
        jdk_result,
        jar_check(environ),
        kiro_check(root),
        daemon_check(root, environ),
    ]


def has_failures(report: list[Check]) -> bool:
    return any(level == "fail" for level, _, _ in report)
