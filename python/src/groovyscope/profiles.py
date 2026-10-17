"""Runtime profiles: discovery, validation and the view the engine receives (see docs/profiles.md).

Profiles are YAML or JSON files. They are found, in this order, in the paths listed under ``profiles.paths`` in
``.groovyscope.yaml``, in ``<root>/.groovyscope/profiles/`` and in ``~/.groovyscope/profiles/`` (the last two only
with ``profiles.discover`` on). When two files define the same profile name, the first one found wins, so a
workspace can override a profile kept in the home directory.
"""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any, TypedDict

import yaml
from jsonschema import Draft202012Validator

from groovyscope.config import format_error, package_text

PROFILE_SUFFIXES = (".yaml", ".yml", ".json")
WORKSPACE_DIR = Path(".groovyscope") / "profiles"
USER_DIR = Path(".groovyscope") / "profiles"


class LoadedProfile(TypedDict):
    name: str
    path: str
    profile: dict[str, Any]


class Discovery(TypedDict):
    profiles: list[LoadedProfile]
    shadowed: list[str]
    errors: list[str]


def load_profile_schema() -> dict[str, Any]:
    loaded: dict[str, Any] = json.loads(package_text("profile-schema.json"))
    return loaded


def validate_profile(profile: Mapping[str, Any]) -> tuple[str, ...]:
    """Schema violations as ``location: message``; empty means valid."""
    validator = Draft202012Validator(load_profile_schema())
    errors = sorted(validator.iter_errors(dict(profile)), key=lambda err: [str(p) for p in err.absolute_path])
    return tuple(format_error(error) for error in errors)


def read_profile(path: Path) -> dict[str, Any]:
    """Parse one profile file (YAML is a superset of JSON). Raises ``ValueError`` on anything but a mapping."""
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(data, Mapping):
        msg = f"{path}: a profile must be a mapping"
        raise ValueError(msg)
    return dict(data)


def profile_files(entry: Path) -> list[Path]:
    """The profile files an entry names: the file itself, or the profile files directly inside a directory."""
    if entry.is_file():
        return [entry]
    if entry.is_dir():
        return sorted(p for p in entry.iterdir() if p.is_file() and p.suffix.lower() in PROFILE_SUFFIXES)
    return []


def search_path(root: Path, settings: Mapping[str, Any], home: Path) -> list[tuple[Path, bool]]:
    """Where profiles are looked for, in priority order, each with whether it was configured explicitly."""
    explicit = [Path(str(p)).expanduser() for p in settings.get("paths", [])]
    entries = [(p if p.is_absolute() else root / p, True) for p in explicit]
    if settings.get("discover", True):
        entries += [(root / WORKSPACE_DIR, False), (home / USER_DIR, False)]
    return entries


def discover(root: Path, settings: Mapping[str, Any], home: Path) -> Discovery:
    """Load every profile on the search path. Invalid files are reported in ``errors`` and skipped."""
    found: list[LoadedProfile] = []
    shadowed: list[str] = []
    errors: list[str] = []
    seen: set[Path] = set()
    for entry, explicit in search_path(root, settings, home):
        if not entry.exists():
            if explicit:
                errors.append(f"{entry}: no such profile file or directory")
            continue
        for path in profile_files(entry):
            resolved = path.resolve()
            if resolved in seen:
                continue
            seen.add(resolved)
            try:
                profile = read_profile(path)
            except (OSError, ValueError, yaml.YAMLError) as exc:
                errors.append(f"{path}: {exc}")
                continue
            problems = validate_profile(profile)
            if problems:
                errors.extend(f"{path}: {problem}" for problem in problems)
                continue
            name = str(profile["name"])
            if any(loaded["name"] == name for loaded in found):
                shadowed.append(f"{path} (profile {name} already loaded)")
                continue
            found.append({"name": name, "path": str(path), "profile": profile})
    return {"profiles": found, "shadowed": shadowed, "errors": errors}


def engine_profiles(discovery: Discovery) -> list[dict[str, Any]]:
    """The profile documents in the order the engine tries them."""
    return [loaded["profile"] for loaded in discovery["profiles"]]


def guidance(discovery: Discovery) -> str:
    """Steering text for the active profiles that carry guidance; empty when none do."""
    parts = [
        f"## {loaded['name']}\n\n{str(loaded['profile']['guidance']).strip()}\n"
        for loaded in discovery["profiles"]
        if str(loaded["profile"].get("guidance", "")).strip()
    ]
    if not parts:
        return ""
    header = (
        "# Runtime profiles\n\n"
        "These rules come from the runtime profiles active in this workspace. Call `profile_status` on a file "
        "before changing it to see its target Groovy version, descriptor parameters, test seams and protected "
        "methods.\n\n"
    )
    return header + "\n".join(parts)


def contract_drift(baseline: Mapping[str, Mapping[str, str]], current: Mapping[str, Mapping[str, str]]) -> list[str]:
    """Recorded contract items that are gone or changed, one line each. Additions are not drift."""
    lines: list[str] = []
    for path, items in sorted(baseline.items()):
        now = current.get(path)
        if now is None:
            lines.append(f"{path}: governed file is gone or no longer governed")
            continue
        for key, value in sorted(items.items()):
            if key not in now:
                lines.append(f"{path}: {key} is gone (was {value})")
            elif now[key] != value:
                lines.append(f"{path}: {key} changed from {value} to {now[key]}")
    return lines


def describe(discovery: Discovery) -> Sequence[str]:
    """Human-readable lines for ``groovyscope profile list``."""
    lines = [f"{loaded['name']}: {loaded['path']}" for loaded in discovery["profiles"]]
    lines += [f"shadowed: {item}" for item in discovery["shadowed"]]
    lines += [f"error: {item}" for item in discovery["errors"]]
    return lines or ["no runtime profiles found"]
