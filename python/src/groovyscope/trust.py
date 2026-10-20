"""Which workspaces the user trusts. Trust is the user's decision, kept in ``~/.groovyscope/trusted-workspaces.json``;
a repository cannot grant it to itself through its own ``.groovyscope.yaml``.

In an untrusted workspace, settings in the workspace's ``.groovyscope.yaml`` that would run code from the repository are
ignored: ``workspace.trust`` (AST transformations from the workspace classpath), ``jdk.home`` (the ``java`` binary
groovyscope starts), ``lint.ruleset`` / ``lint.jenkinsRuleset`` / ``lint.gradleRuleset`` (CodeNarc rule sets are Groovy
scripts), ``typecheck.configScript`` (a compiler configuration script, run only in a trusted workspace), the emulation
jars of runtime profiles kept in the workspace, ``logging.file`` (a file groovyscope appends to), ``jdk.autoProvision``
/ ``jdk.vendor`` (what is downloaded and run) and ``daemon.heapDumpOnOutOfMemory`` (a dump the size of the heap). Its
heap settings are capped at ``daemon.untrustedHeapCeiling``, and the report paths it names (``reports.sarif``,
``reports.jacoco``) must stay inside the workspace.
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from groovyscope import atomic
from groovyscope.selfheal import format_heap, parse_heap

TRUST_FILE = Path(".groovyscope") / "trusted-workspaces.json"
# (section, key) pairs a workspace file may set only when the workspace is trusted.
GATED_KEYS: tuple[tuple[str, str], ...] = (
    ("workspace", "trust"),
    ("jdk", "home"),
    ("lint", "ruleset"),
    ("lint", "jenkinsRuleset"),
    ("lint", "gradleRuleset"),
    ("typecheck", "configScript"),
    ("logging", "file"),
    ("jdk", "autoProvision"),
    ("jdk", "vendor"),
    ("daemon", "heapDumpOnOutOfMemory"),
)
# Environment variables that mark a process an agent started (Kiro's hooks and tools, Claude Code): trust is never
# granted from one without the user saying so at a terminal. KIRO_HOME is a user setting, not a session.
AGENT_ENV_PREFIXES = ("KIRO_",)
AGENT_ENV_NAMES = ("CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT")
NOT_AGENT_ENV = ("KIRO_HOME",)
# Heap settings an untrusted workspace file may lower but not raise past daemon.untrustedHeapCeiling.
CAPPED_HEAP_KEYS = ("heap", "maxHeap")


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


def agent_session(environ: Mapping[str, str]) -> str | None:
    """The environment variable showing that an agent started this process, or None."""
    for name in sorted(environ):
        if name in NOT_AGENT_ENV or not environ[name]:
            continue
        if name in AGENT_ENV_NAMES or name.startswith(AGENT_ENV_PREFIXES):
            return name
    return None


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
    atomic.write_text(target, json.dumps({"workspaces": sorted(roots)}, indent=2) + "\n", mode=atomic.PRIVATE_MODE)
    return sorted(roots)


def gate(
    merged: dict[str, Any],
    from_file: Mapping[str, Any],
    from_env: Mapping[str, Any],
    defaults: Mapping[str, Any],
    *,
    trusted: bool,
    root: Path | None = None,
) -> tuple[dict[str, Any], list[str]]:
    """Undo code-running settings a workspace file made in an untrusted workspace (environment overrides, which
    the user controls, stay). ``workspace.trust`` itself becomes the user's decision. Heap settings are capped and,
    with ``root``, report paths outside it are dropped."""
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
    if not trusted:
        notes += cap_heap(result, from_file, from_env, defaults)
        if root is not None:
            notes += confine_reports(result, from_file, from_env, defaults, root)
    return (result, notes)


def file_sets(from_file: Mapping[str, Any], from_env: Mapping[str, Any], section: str, key: str) -> bool:
    """Whether the workspace file (and not an environment override) chose ``section.key``."""
    in_file = isinstance(from_file.get(section), Mapping) and key in from_file[section]
    in_env = isinstance(from_env.get(section), Mapping) and key in from_env[section]
    return in_file and not in_env


def cap_heap(
    result: dict[str, Any], from_file: Mapping[str, Any], from_env: Mapping[str, Any], defaults: Mapping[str, Any]
) -> list[str]:
    """``daemon.heap`` and ``daemon.maxHeap`` from an untrusted workspace file, lowered to the ceiling."""
    daemon = result.get("daemon")
    if not isinstance(daemon, dict):
        return []
    ceiling_text = str(daemon.get("untrustedHeapCeiling") or defaults["daemon"]["untrustedHeapCeiling"])
    if file_sets(from_file, from_env, "daemon", "untrustedHeapCeiling"):
        ceiling_text = str(defaults["daemon"]["untrustedHeapCeiling"])
        daemon["untrustedHeapCeiling"] = ceiling_text
    ceiling = parse_heap(ceiling_text)
    notes: list[str] = []
    for key in CAPPED_HEAP_KEYS:
        value = str(daemon.get(key) or "")
        if not file_sets(from_file, from_env, "daemon", key) or value.lower() == "auto":
            continue
        try:
            asked = parse_heap(value)
        except ValueError:
            continue
        if asked > ceiling:
            daemon[key] = format_heap(ceiling)
            notes.append(
                f"daemon.{key} {value} from .groovyscope.yaml is capped at {ceiling_text} in an untrusted workspace"
            )
    return notes


def inside(root: Path, value: str) -> bool:
    path = Path(value).expanduser()
    resolved = (path if path.is_absolute() else root / path).resolve()
    return resolved.is_relative_to(root.resolve())


def confine_reports(
    result: dict[str, Any],
    from_file: Mapping[str, Any],
    from_env: Mapping[str, Any],
    defaults: Mapping[str, Any],
    root: Path,
) -> list[str]:
    """``reports.sarif`` and ``reports.jacoco`` from an untrusted workspace file, kept inside the workspace."""
    reports = result.get("reports")
    if not isinstance(reports, dict):
        return []
    notes: list[str] = []
    if file_sets(from_file, from_env, "reports", "sarif"):
        kept = [str(p) for p in reports.get("sarif") or [] if inside(root, str(p))]
        if len(kept) != len(reports.get("sarif") or []):
            notes.append("reports.sarif paths outside the workspace are ignored in an untrusted workspace")
        reports["sarif"] = kept
    jacoco = reports.get("jacoco")
    if file_sets(from_file, from_env, "reports", "jacoco") and jacoco and not inside(root, str(jacoco)):
        reports["jacoco"] = defaults["reports"]["jacoco"]
        notes.append("reports.jacoco outside the workspace is ignored in an untrusted workspace")
    return notes
