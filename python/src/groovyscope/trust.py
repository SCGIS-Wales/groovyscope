"""Which workspaces the user trusts. Trust is the user's decision, kept in ``~/.groovyscope/trusted-workspaces.json``;
a repository cannot grant it to itself through its own ``.groovyscope.yaml``.

In an untrusted workspace, settings in the workspace's ``.groovyscope.yaml`` that would run code from the repository
are ignored: ``workspace.trust`` (AST transformations from the workspace classpath), ``jdk.home`` (the ``java``
binary groovyscope starts), ``lint.ruleset`` / ``lint.jenkinsRuleset`` (CodeNarc rule sets are Groovy scripts), and
the emulation jars of runtime profiles kept in the workspace.
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from pathlib import Path
from typing import Any

TRUST_FILE = Path(".groovyscope") / "trusted-workspaces.json"
# (section, key) pairs a workspace file may set only when the workspace is trusted.
GATED_KEYS: tuple[tuple[str, str], ...] = (
    ("workspace", "trust"),
    ("jdk", "home"),
    ("lint", "ruleset"),
    ("lint", "jenkinsRuleset"),
)


def trust_file(home: Path) -> Path:
    return home / TRUST_FILE


def trusted_roots(home: Path) -> list[str]:
    """Absolute workspace paths the user trusts; an unreadable or malformed file trusts nothing."""
    try:
        data = json.loads(trust_file(home).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    roots = data.get("workspaces", []) if isinstance(data, dict) else []
    return [str(root) for root in roots if isinstance(root, str)]


def is_trusted(root: Path, home: Path) -> bool:
    return str(root.resolve()) in trusted_roots(home)


def set_trusted(root: Path, home: Path, *, trusted: bool) -> list[str]:
    """Add or remove ``root`` and return the new list. The file is private to the user."""
    resolved = str(root.resolve())
    roots = [r for r in trusted_roots(home) if r != resolved]
    if trusted:
        roots.append(resolved)
    target = trust_file(home)
    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = target.with_name(target.name + ".tmp")
    temporary.write_text(json.dumps({"workspaces": sorted(roots)}, indent=2) + "\n", encoding="utf-8")
    temporary.chmod(0o600)
    temporary.replace(target)
    return sorted(roots)


def gate(
    merged: dict[str, Any],
    from_file: Mapping[str, Any],
    from_env: Mapping[str, Any],
    defaults: Mapping[str, Any],
    *,
    trusted: bool,
) -> tuple[dict[str, Any], list[str]]:
    """Undo code-running settings a workspace file made in an untrusted workspace (environment overrides, which
    the user controls, stay). ``workspace.trust`` itself becomes the user's decision."""
    notes: list[str] = []
    result = {section: dict(value) if isinstance(value, Mapping) else value for section, value in merged.items()}
    for section, key in GATED_KEYS:
        file_value = (from_file.get(section) or {}).get(key) if isinstance(from_file.get(section), Mapping) else None
        env_section = from_env.get(section)
        env_set = isinstance(env_section, Mapping) and key in env_section
        if (section, key) == ("workspace", "trust"):
            result["workspace"]["trust"] = bool(env_section[key]) if env_set else trusted  # type: ignore[index]
            if file_value and not trusted and not env_set:
                notes.append("workspace.trust in .groovyscope.yaml is ignored until you run `groovyscope trust` here")
            continue
        if trusted or env_set or file_value in (None, ""):
            continue
        result[section][key] = defaults[section][key]
        notes.append(
            f"{section}.{key} from .groovyscope.yaml is ignored in an untrusted workspace (`groovyscope trust`)"
        )
    return (result, notes)
