from __future__ import annotations

from pathlib import Path

import pytest
import yaml
from hypothesis import given
from hypothesis import strategies as st

from groovyscope.config import (
    CONFIG_FILENAME,
    deep_merge,
    dump_yaml,
    env_overrides,
    find_config_file,
    load_config,
    load_defaults,
    load_schema,
    match_key,
    parse_scalar,
    read_yaml_file,
    validate_config,
)

scalars = st.one_of(st.integers(), st.text(max_size=8), st.booleans(), st.none())
keys = st.text(alphabet="abcXYZ", min_size=1, max_size=3)
nested = st.recursive(scalars, lambda inner: st.dictionaries(keys, inner, max_size=4), max_leaves=12)
mappings = st.dictionaries(keys, nested, max_size=5)


def test_defaults_are_valid() -> None:
    assert validate_config(load_defaults()) == ()


def test_schema_rejects_unknown_top_level_keys() -> None:
    assert load_schema()["additionalProperties"] is False
    errors = validate_config({**load_defaults(), "bogus": 1})
    assert errors and "bogus" in errors[0]


@given(mappings)
def test_deep_merge_identity(base: dict[str, object]) -> None:
    assert deep_merge(base, {}) == base
    assert deep_merge({}, base) == base


@given(mappings, mappings)
def test_deep_merge_keys_are_the_union_and_override_wins(base: dict[str, object], over: dict[str, object]) -> None:
    merged = deep_merge(base, over)
    assert set(merged) == set(base) | set(over)
    for key, value in over.items():
        if not isinstance(value, dict) or not isinstance(base.get(key), dict):
            assert merged[key] == value


def test_deep_merge_does_not_mutate_inputs() -> None:
    base = {"a": {"b": 1}}
    over = {"a": {"c": 2}}
    merged = deep_merge(base, over)
    assert merged == {"a": {"b": 1, "c": 2}}
    assert base == {"a": {"b": 1}}
    assert over == {"a": {"c": 2}}


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("true", True),
        ("17", 17),
        ("2g", "2g"),
        ("null", None),
        ("[a, b]", ["a", "b"]),
        ("", ""),
        ("[unclosed", "[unclosed"),
    ],
)
def test_parse_scalar(raw: str, expected: object) -> None:
    assert parse_scalar(raw) == expected


def test_match_key_recovers_camel_case() -> None:
    assert match_key({"idleTimeoutMinutes": 1}, "IDLETIMEOUTMINUTES") == "idleTimeoutMinutes"
    assert match_key({"a": 1}, "UNKNOWN") == "unknown"


def test_env_overrides_nest_and_parse() -> None:
    env = {
        "GROOVYSCOPE_DAEMON__HEAP": "2g",
        "GROOVYSCOPE_DAEMON__IDLETIMEOUTMINUTES": "30",
        "GROOVYSCOPE_WORKSPACE__TRUST": "true",
        "GROOVYSCOPE_JAR": "/ignored.jar",
        "GROOVYSCOPE_LOG": "debug",
        "GROOVYSCOPE_": "ignored",
        "GROOVYSCOPE_DAEMON__": "ignored",
        "OTHER": "ignored",
    }
    assert env_overrides(env, load_defaults()) == {
        "daemon": {"heap": "2g", "idleTimeoutMinutes": 30},
        "workspace": {"trust": True},
    }


def test_load_config_layers_defaults_file_and_env(tmp_path: Path) -> None:
    (tmp_path / CONFIG_FILENAME).write_text("daemon:\n  heap: 3g\n  cds: false\n", encoding="utf-8")
    config = load_config(tmp_path, None, {"GROOVYSCOPE_DAEMON__HEAP": "4g"})
    assert config["daemon"]["heap"] == "4g"
    assert config["daemon"]["cds"] is False
    assert config["daemon"]["idleTimeoutMinutes"] == 60


def test_load_config_reports_every_violation(tmp_path: Path) -> None:
    (tmp_path / CONFIG_FILENAME).write_text("daemon:\n  heap: lots\njdk:\n  minimum: 8\n", encoding="utf-8")
    with pytest.raises(ValueError, match="invalid configuration") as excinfo:
        load_config(tmp_path, None, {})
    message = str(excinfo.value)
    assert "daemon.heap" in message
    assert "jdk.minimum" in message


def test_load_config_explicit_path_and_missing_file(tmp_path: Path) -> None:
    explicit = tmp_path / "custom.yaml"
    explicit.write_text("output:\n  maxResults: 5\n", encoding="utf-8")
    assert load_config(tmp_path, explicit, {})["output"]["maxResults"] == 5
    with pytest.raises(OSError, match=r"nope\.yaml"):
        load_config(tmp_path, tmp_path / "nope.yaml", {})


def test_read_yaml_file_edge_cases(tmp_path: Path) -> None:
    empty = tmp_path / "empty.yaml"
    empty.write_text("", encoding="utf-8")
    assert read_yaml_file(empty) == {}
    listing = tmp_path / "list.yaml"
    listing.write_text("- a\n- b\n", encoding="utf-8")
    with pytest.raises(ValueError, match="top level must be a mapping"):
        read_yaml_file(listing)


def test_find_config_file(tmp_path: Path) -> None:
    assert find_config_file(tmp_path, None) is None
    target = tmp_path / CONFIG_FILENAME
    target.write_text("", encoding="utf-8")
    assert find_config_file(tmp_path, None) == target
    assert find_config_file(tmp_path, Path("x.yaml")) == Path("x.yaml")


def test_dump_yaml_round_trips_defaults() -> None:
    defaults = load_defaults()
    assert yaml.safe_load(dump_yaml(defaults)) == defaults
    assert dump_yaml(defaults).startswith("groovy:")
