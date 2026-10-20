"""Configuration: packaged defaults, then ``.groovyscope.yaml``, then ``GROOVYSCOPE_*`` environment overrides."""

from __future__ import annotations

import copy
import functools
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
# The environment variables that configure groovyscope itself rather than the YAML document; every module reads
# the names from here.
JAR_ENV = "GROOVYSCOPE_JAR"  # an engine JAR instead of the bundled one
LOG_ENV = "GROOVYSCOPE_LOG"  # log level
CONFIG_FILE_ENV = "GROOVYSCOPE_CONFIG"  # an explicit configuration file
CONFIG_JSON_ENV = "GROOVYSCOPE_CONFIG_JSON"  # the engine configuration a front end hands to the engine
SOCKET_DIR_ENV = "GROOVYSCOPE_SOCKET_DIR"  # where daemon sockets live
CACHE_DIR_ENV = "GROOVYSCOPE_CACHE_DIR"  # where caches (class data sharing archives) live
BENCHMARK_DIR_ENV = "GROOVYSCOPE_BENCHMARK_DIR"  # where the benchmark corpus is unpacked
RESERVED_ENV = frozenset(
    {JAR_ENV, LOG_ENV, CONFIG_FILE_ENV, CONFIG_JSON_ENV, SOCKET_DIR_ENV, CACHE_DIR_ENV, BENCHMARK_DIR_ENV}
)

Config = dict[str, Any]


def package_text(relative: str) -> str:
    """Read a UTF-8 text resource shipped inside the ``groovyscope`` package."""
    return resources.files("groovyscope").joinpath(relative).read_text(encoding="utf-8")


def load_defaults() -> Config:
    """The documented defaults from ``defaults.yaml``."""
    data = yaml.safe_load(package_text("defaults.yaml"))
    return dict(data)


@functools.cache
def _bundled_defaults() -> Config:
    return load_defaults()


def default(section: str, key: str) -> Any:
    """The documented default of ``section.key`` in ``defaults.yaml``, read once: the one source for a fallback when
    a settings mapping lacks the key (a partial mapping from a test or an older caller). An unknown key raises
    ``KeyError``, so a typo never falls back silently."""
    return copy.deepcopy(_bundled_defaults()[section][key])


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


JSON_TYPES: tuple[tuple[type, str], ...] = (
    (bool, "boolean"),
    (int, "integer"),
    (float, "number"),
    (str, "string"),
    (list, "array"),
    (dict, "object"),
)


def json_type(value: Any) -> str:
    """The JSON schema type of a parsed value (``bool`` before ``int``: a bool is an int in Python)."""
    if value is None:
        return "null"
    return next((name for kind, name in JSON_TYPES if isinstance(value, kind)), "string")


def schema_node(schema: Mapping[str, Any], keys: Sequence[str]) -> Mapping[str, Any]:
    """The schema that governs the setting at ``keys`` (empty when the schema says nothing about it)."""
    node: Mapping[str, Any] = schema
    for key in keys:
        properties = node.get("properties") or {}
        extra = node.get("additionalProperties")
        if key in properties:
            node = properties[key]
        elif isinstance(extra, Mapping):
            node = extra
        else:
            return {}
    return node


def coerce(raw: str, node: Mapping[str, Any]) -> Any:
    """An environment value as the setting's schema type: YAML scalar rules first, but a value the schema wants as a
    string stays the text it was written as (``GROOVYSCOPE_GROOVY__VERSION=6.0`` is ``"6.0"``, not ``6.0``)."""
    parsed = parse_scalar(raw)
    declared = node.get("type")
    allowed = {declared} if isinstance(declared, str) else set(declared or ())
    found = json_type(parsed)
    if not allowed or found in allowed or (found == "integer" and "number" in allowed):
        return parsed
    return raw if "string" in allowed else parsed


def env_keys(parts: Sequence[str], reference: Mapping[str, Any]) -> list[str]:
    """The camelCase keys an environment variable's segments name."""
    keys: list[str] = []
    node: Any = reference
    for part in parts:
        key = match_key(node if isinstance(node, Mapping) else {}, part)
        keys.append(key)
        node = node.get(key) if isinstance(node, Mapping) else None
    return keys


def env_overrides(environ: Mapping[str, str], reference: Mapping[str, Any]) -> Config:
    """Collect ``GROOVYSCOPE_SECTION__KEY=value`` variables into a nested mapping, each value coerced to its
    setting's schema type. Variables that name no configuration section (``GROOVYSCOPE_TOKEN`` and the like) are
    not configuration and are skipped."""
    overrides: Config = {}
    sections = {key.lower() for key in reference}
    schema = load_schema()
    for name, raw in sorted(environ.items()):
        if not name.startswith(ENV_PREFIX) or name in RESERVED_ENV:
            continue
        parts = name[len(ENV_PREFIX) :].split(ENV_SEPARATOR)
        if any(part == "" for part in parts) or parts[0].lower() not in sections:
            continue
        value = coerce(raw, schema_node(schema, env_keys(parts, reference)))
        overrides = deep_merge(overrides, nest(parts, value, reference))
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
    """A YAML mapping, with YAML syntax errors and text that is not UTF-8 reported as ``ValueError`` naming the
    file."""
    try:
        return read_yaml_file(path)
    except yaml.YAMLError as exc:
        msg = f"{path} is not valid YAML: {exc}"
        raise ValueError(msg) from exc
    except UnicodeDecodeError as exc:
        msg = f"{path} is not UTF-8 text ({exc.reason} at byte {exc.start}); save it as UTF-8"
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
    return trust.gate(merged, workspace_file, from_env, defaults, trusted=trusted, root=root)


def load_config(
    root: Path, explicit: Path | None = None, environ: Mapping[str, str] | None = None, home: Path | None = None
) -> Config:
    """Effective configuration for ``root`` (see :func:`load_config_with_notes`)."""
    return load_config_with_notes(root, explicit, environ, home)[0]


def dump_yaml(config: Mapping[str, Any]) -> str:
    """Render a configuration as YAML in a stable key order."""
    return yaml.safe_dump(dict(config), sort_keys=False, default_flow_style=False)
