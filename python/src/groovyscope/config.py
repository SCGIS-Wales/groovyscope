"""Configuration: packaged defaults, then ``.groovyscope.yaml``, then ``GROOVYSCOPE_*`` environment overrides."""

from __future__ import annotations

import json
import os
from collections.abc import Mapping, Sequence
from importlib import resources
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator
from jsonschema.exceptions import ValidationError

from groovyscope import trust

CONFIG_FILENAME = ".groovyscope.yaml"
ENV_PREFIX = "GROOVYSCOPE_"
ENV_SEPARATOR = "__"
# Environment variables that configure the CLI itself rather than the YAML document.
RESERVED_ENV = frozenset(
    {
        "GROOVYSCOPE_JAR",
        "GROOVYSCOPE_LOG",
        "GROOVYSCOPE_CONFIG",
        "GROOVYSCOPE_CONFIG_JSON",
        "GROOVYSCOPE_SOCKET_DIR",
        "GROOVYSCOPE_CACHE_DIR",
    }
)

Config = dict[str, Any]


def package_text(relative: str) -> str:
    """Read a UTF-8 text resource shipped inside the ``groovyscope`` package."""
    return resources.files("groovyscope").joinpath(relative).read_text(encoding="utf-8")


def load_defaults() -> Config:
    """The documented defaults from ``defaults.yaml``."""
    data = yaml.safe_load(package_text("defaults.yaml"))
    return dict(data)


def load_schema() -> dict[str, Any]:
    """The JSON schema every effective configuration must satisfy."""
    loaded: dict[str, Any] = json.loads(package_text("config-schema.json"))
    return loaded


def read_yaml_file(path: Path) -> Config:
    """Parse a YAML mapping; an empty file is an empty mapping."""
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if data is None:
        return {}
    if not isinstance(data, Mapping):
        msg = f"{path}: top level must be a mapping, got {type(data).__name__}"
        raise ValueError(msg)
    return dict(data)


def deep_merge(base: Mapping[str, Any], override: Mapping[str, Any]) -> Config:
    """Return a new mapping where nested mappings merge recursively and everything else is replaced."""
    merged: Config = dict(base)
    for key, value in override.items():
        current = merged.get(key)
        if isinstance(current, Mapping) and isinstance(value, Mapping):
            merged[key] = deep_merge(current, value)
        else:
            merged[key] = value
    return merged


def match_key(reference: Mapping[str, Any], name: str) -> str:
    """Resolve an environment-variable segment to the camelCase key it names, case-insensitively."""
    lowered = name.lower()
    for key in reference:
        if key.lower() == lowered:
            return key
    return lowered


def parse_scalar(raw: str) -> Any:
    """Interpret an environment value with YAML scalar rules (``true``, ``17``, ``null``, ``[a, b]``)."""
    if raw == "":
        return ""
    try:
        return yaml.safe_load(raw)
    except yaml.YAMLError:
        return raw


def nest(parts: Sequence[str], value: Any, reference: Mapping[str, Any]) -> Config:
    """Build ``{a: {b: value}}`` from ``["a", "b"]`` using ``reference`` to recover key casing."""
    key = match_key(reference, parts[0])
    if len(parts) == 1:
        return {key: value}
    child = reference.get(key)
    child_reference = child if isinstance(child, Mapping) else {}
    return {key: nest(parts[1:], value, child_reference)}


def env_overrides(environ: Mapping[str, str], reference: Mapping[str, Any]) -> Config:
    """Collect ``GROOVYSCOPE_SECTION__KEY=value`` variables into a nested mapping. Variables that name no
    configuration section (``GROOVYSCOPE_TOKEN`` and the like) are not configuration and are skipped."""
    overrides: Config = {}
    sections = {key.lower() for key in reference}
    for name, raw in sorted(environ.items()):
        if not name.startswith(ENV_PREFIX) or name in RESERVED_ENV:
            continue
        parts = name[len(ENV_PREFIX) :].split(ENV_SEPARATOR)
        if any(part == "" for part in parts) or parts[0].lower() not in sections:
            continue
        overrides = deep_merge(overrides, nest(parts, parse_scalar(raw), reference))
    return overrides


def format_error(error: ValidationError) -> str:
    location = ".".join(str(part) for part in error.absolute_path) or "<root>"
    return f"{location}: {error.message}"


def validate_config(config: Mapping[str, Any]) -> tuple[str, ...]:
    """Return schema violations as ``location: message`` strings; empty means valid."""
    validator = Draft202012Validator(load_schema())
    errors = sorted(validator.iter_errors(dict(config)), key=lambda err: [str(p) for p in err.absolute_path])
    return tuple(format_error(error) for error in errors)


def find_config_file(root: Path, explicit: Path | None) -> Path | None:
    """An explicit path always wins; otherwise ``.groovyscope.yaml`` in the root when present."""
    if explicit is not None:
        return explicit
    candidate = root / CONFIG_FILENAME
    return candidate if candidate.is_file() else None


def read_config_file(path: Path) -> Config:
    """A YAML mapping, with YAML syntax errors reported as ``ValueError``."""
    try:
        return read_yaml_file(path)
    except yaml.YAMLError as exc:
        msg = f"{path} is not valid YAML: {exc}"
        raise ValueError(msg) from exc


def load_config_with_notes(
    root: Path,
    explicit: Path | None = None,
    environ: Mapping[str, str] | None = None,
    home: Path | None = None,
) -> tuple[Config, list[str]]:
    """Effective configuration for ``root`` and notes about settings ignored because the workspace is not trusted.
    A file passed explicitly (``--config``, ``GROOVYSCOPE_CONFIG``) is the user's choice and is not gated; the
    workspace's own ``.groovyscope.yaml`` is. Raises ``ValueError`` listing every schema violation."""
    env = os.environ if environ is None else environ
    defaults = load_defaults()
    path = find_config_file(root, explicit)
    from_file = read_config_file(path) if path is not None else {}
    from_env = env_overrides(env, defaults)
    merged = deep_merge(deep_merge(defaults, from_file), from_env)
    errors = validate_config(merged)
    if errors:
        listing = "\n".join(f"  - {error}" for error in errors)
        msg = f"invalid configuration:\n{listing}"
        raise ValueError(msg)
    workspace_file = from_file if explicit is None else {}
    trusted = trust.is_trusted(root, home or Path.home()) or (
        explicit is not None and bool((from_file.get("workspace") or {}).get("trust"))
    )
    return trust.gate(merged, workspace_file, from_env, defaults, trusted=trusted)


def load_config(
    root: Path, explicit: Path | None = None, environ: Mapping[str, str] | None = None, home: Path | None = None
) -> Config:
    """Effective configuration for ``root`` (see :func:`load_config_with_notes`)."""
    return load_config_with_notes(root, explicit, environ, home)[0]


def dump_yaml(config: Mapping[str, Any]) -> str:
    """Render a configuration as YAML in a stable key order."""
    return yaml.safe_dump(dict(config), sort_keys=False, default_flow_style=False)
