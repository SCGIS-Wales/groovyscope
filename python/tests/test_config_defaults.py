"""Settings fall back to defaults.yaml, never to a literal of their own (docs/improvement-plan.md, item 6.4)."""

from __future__ import annotations

import ast
import re
from pathlib import Path
from typing import Any

import pytest

from groovyscope import config

SOURCES = Path(__file__).resolve().parents[1] / "src" / "groovyscope"
# Fields of engine replies, manifests and report rows that share a setting's name; their fallbacks are not settings.
NOT_SETTINGS = frozenset({"version", "severity", "level", "engine"})
# A lookahead, so a fallback nested in another .get(...) is seen too.
FALLBACK = re.compile(r'(?=\.get\(\s*"(?P<key>[A-Za-z0-9_]+)"\s*,\s*(?P<value>[^)\n]+?)\))')
NO_VALUE = (None, {}, [], (), "")


def setting_keys(document: dict[str, Any]) -> set[str]:
    """Every key of ``defaults.yaml`` whose default is a value rather than a section."""
    keys: set[str] = set()
    for key, value in document.items():
        if isinstance(value, dict):
            keys |= setting_keys(value)
        else:
            keys.add(key)
    return keys


def literal(text: str) -> Any:
    """The value of a Python literal, or ``None`` for anything else (a name, a call)."""
    try:
        return ast.literal_eval(text)
    except (ValueError, SyntaxError):
        return None


def literal_fallbacks(text: str, keys: set[str]) -> list[str]:
    found = []
    for number, line in enumerate(text.splitlines(), 1):
        for match in FALLBACK.finditer(line):
            key, value = match.group("key"), match.group("value").strip()
            if key in keys and key not in NOT_SETTINGS and literal(value) not in NO_VALUE:
                found.append(f"{number}: {line.strip()}")
    return found


def test_no_source_falls_back_to_a_literal_for_a_setting() -> None:
    keys = setting_keys(config.load_defaults())
    offenders = {
        path.name: hits
        for path in sorted(SOURCES.rglob("*.py"))
        if (hits := literal_fallbacks(path.read_text(encoding="utf-8"), keys))
    }
    assert offenders == {}


def test_the_scan_catches_a_literal_fallback() -> None:
    assert literal_fallbacks('heap = settings.get("heap", "1g")', {"heap"}) == ['1: heap = settings.get("heap", "1g")']
    assert literal_fallbacks('heap = settings.get("heap", config.default("daemon", "heap"))', {"heap"}) == []
    nested = 'on = bool(section.get("stopGate", defaults.get("stopGate", False)))'
    assert literal_fallbacks(nested, {"stopGate"}) == [f"1: {nested}"]


def test_default_reads_defaults_yaml_once_and_refuses_unknown_keys() -> None:
    assert config.default("daemon", "heap") == config.load_defaults()["daemon"]["heap"]
    first = config.default("verify", "runTasks")
    first.append("changed")
    assert config.default("verify", "runTasks") == config.load_defaults()["verify"]["runTasks"]  # a copy each time
    with pytest.raises(KeyError):
        config.default("daemon", "heapp")
