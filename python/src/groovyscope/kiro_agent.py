"""Kiro agent file formats: detect what a ``groovy.json`` agent is, and merge groovyscope's entries into it
without changing which Kiro engines can load it.

Kiro CLI 2.x knows these agent formats (``kiro-cli agent upgrade`` names them):

- ``v2``: ``tools`` / ``allowedTools`` / ``toolsSettings``; loaded by the V2 engine (the 2.x default).
- ``universal``: the V2 fields plus a V3 ``permissions`` block and a ``kiroUpgrade`` stamp; loaded by both.
- ``universal-out-of-sync``: a universal file whose V2 fields changed after the stamp.
- ``v3``: a ``permissions`` block without the stamp; loaded only by the V3 engine.

A ``kiroUpgrade`` revision newer than :data:`KNOWN_UPGRADE_REVISION` is a format groovyscope does not know; such a
file, and one Kiro reports as managed externally, is left untouched.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any, TypedDict

from groovyscope import process
from groovyscope.config import deep_merge
from groovyscope.tools_catalog import SAFE_SHELL_GLOBS, mutating_tools, read_only_tools, v3_matches

KNOWN_UPGRADE_REVISION = 2
FORMATS = ("v2", "universal", "universal-out-of-sync", "v3", "unknown", "external")
REQUESTABLE = ("auto", "v2", "universal", "v3")
V2_KEYS = ("tools", "allowedTools", "toolsSettings")
V3_KEYS = ("permissions", "kiroUpgrade")
# List-valued agent keys where a user's own entries are kept next to groovyscope's.
UNION_PATHS: tuple[tuple[str, ...], ...] = (
    ("tools",),
    ("allowedTools",),
    ("resources",),
    ("toolsSettings", "shell", "allowedCommands"),
    ("hooks", "agentSpawn"),
    ("hooks", "userPromptSubmit"),
    ("hooks", "postToolUse"),
    ("hooks", "stop"),
)
# Keys both engines read, merged into a V3-only agent as well (hooks run the groovyscope checkup at start).
SHARED_KEYS = ("hooks", "welcomeMessage", "resources")
# Entries earlier releases wrote that are replaced on merge: the bare `@groovyscope` allowed every tool, the
# per-tool entries from the catalogue allow only the read-only ones (Kiro ignores `readOnlyHint`).
RETIRED_ALLOWED_TOOLS = ("@groovyscope",)
RETIRED_V3_RULES: tuple[dict[str, Any], ...] = (
    {"capability": "mcp", "match": ["groovyscope/*"], "effect": "allow"},
    {"capability": "shell", "match": ["./gradlew *", "groovyscope *", "git status", "git diff*"], "effect": "allow"},
)
# Shell entries earlier releases wrote, which let an agent run any groovyscope command (`trust` included), any Gradle
# task and `git diff --output`; replaced by the read-only list from the tool catalogue.
RETIRED_SHELL_COMMANDS = ("\\./gradlew .*", "groovyscope .*", "git diff.*")


def v3_rules() -> tuple[dict[str, Any], ...]:
    """The V3 rules groovyscope needs: read-only MCP tools allowed, the mutating ones ask, file reads and the
    read-only groovyscope, test and git commands allowed. Derived from the tool catalogue, never written by hand."""
    return (
        {"capability": "mcp", "match": v3_matches(read_only_tools()), "effect": "allow"},
        {"capability": "mcp", "match": v3_matches(mutating_tools()), "effect": "ask"},
        {"capability": "fs_read", "effect": "allow"},
        {"capability": "shell", "match": list(SAFE_SHELL_GLOBS), "effect": "allow"},
    )


def major_version(version: str | None) -> int | None:
    """``3`` from ``3.0.1``; None when the version is unknown or not numeric."""
    if not version:
        return None
    head = version.split(".", 1)[0]
    return int(head) if head.isdigit() else None


KIRO_TIMEOUT_SECONDS = 60

KiroRunner = Callable[[Sequence[str]], tuple[int, str]]


class AgentMerge(TypedDict):
    """The merged agent text, the format it ends up in, and anything the user should know."""

    text: str
    format: str
    note: str


def kiro_runner(which: Callable[[str], str | None] = shutil.which) -> KiroRunner | None:
    """A runner for the installed ``kiro-cli``, or None when it is not on PATH."""
    executable = which("kiro-cli")
    if executable is None:
        return None
    found: str = executable

    def run(arguments: Sequence[str]) -> tuple[int, str]:
        try:
            done = process.run_text([found, *arguments], timeout=KIRO_TIMEOUT_SECONDS)
        except (OSError, subprocess.TimeoutExpired) as exc:
            return (-1, str(exc))
        return (done.returncode, done.stdout + done.stderr)

    return run


def kiro_version(run: KiroRunner | None) -> str | None:
    """``2.32.0`` from ``kiro-cli --version``."""
    if run is None:
        return None
    code, output = run(["--version"])
    words = output.split()
    return words[-1] if code == 0 and words else None


def supports_upgrade(run: KiroRunner | None) -> bool:
    """Whether this kiro-cli can write the universal format (``kiro-cli agent upgrade``)."""
    if run is None:
        return False
    code, output = run(["agent", "upgrade", "--help"])
    return code == 0 and "universal" in output


def detect_format(agent: Mapping[str, Any]) -> str:
    """The format of an agent document, from its keys alone."""
    stamp = agent.get("kiroUpgrade")
    if stamp is not None:
        revision = stamp.get("revision") if isinstance(stamp, Mapping) else None
        if not isinstance(revision, int) or revision > KNOWN_UPGRADE_REVISION:
            return "unknown"
        return "universal"
    if "permissions" in agent:
        return "v3"
    return "v2"


DRY_RUN_VERDICTS: tuple[tuple[str, str], ...] = (
    ("already universal", "universal"),
    ("v3-only", "v3"),
    ("managed externally", "external"),
)


def classify(text: str, run: KiroRunner | None) -> str:
    """The format of an agent file, asking ``kiro-cli agent upgrade --dry-run`` when available (it alone can tell
    an in-sync universal file from an out-of-sync one); otherwise from the keys."""
    try:
        agent = json.loads(text)
    except json.JSONDecodeError:
        return "unknown"
    by_keys = detect_format(agent) if isinstance(agent, dict) else "unknown"
    if run is None or by_keys == "unknown":
        return by_keys
    with tempfile.TemporaryDirectory(prefix="groovyscope-agent-") as folder:
        Path(folder, "groovy.json").write_text(text, encoding="utf-8")
        code, output = run(["agent", "upgrade", "--dry-run", folder])
    verdict = next((name for marker, name in DRY_RUN_VERDICTS if marker in output), None)
    if code != 0 or verdict is not None:
        return verdict or by_keys
    if "would upgrade" in output and by_keys == "universal":
        return "universal-out-of-sync"
    return by_keys


def upgrade(text: str, run: KiroRunner | None) -> str | None:
    """The universal form of a V2 agent, written by ``kiro-cli agent upgrade`` in a scratch folder; None when
    kiro-cli cannot do it."""
    if run is None:
        return None
    with tempfile.TemporaryDirectory(prefix="groovyscope-agent-") as folder:
        target = Path(folder, "groovy.json")
        target.write_text(text, encoding="utf-8")
        code, _ = run(["agent", "upgrade", "--no-backup", folder])
        result = target.read_text(encoding="utf-8")
    if code != 0:
        return None
    try:
        upgraded = json.loads(result)
    except json.JSONDecodeError:
        return None
    return result if isinstance(upgraded, dict) and detect_format(upgraded) == "universal" else None


def union(existing: Any, ours: Any) -> Any:
    """Existing entries first, then ours that are missing."""
    if not isinstance(existing, list):
        return ours
    if not isinstance(ours, list):
        return existing
    return [*existing, *(item for item in ours if item not in existing)]


def get_path(document: Mapping[str, Any], path: Sequence[str]) -> Any:
    node: Any = document
    for key in path:
        if not isinstance(node, Mapping) or key not in node:
            return None
        node = node[key]
    return node


def set_path(document: dict[str, Any], path: Sequence[str], value: Any) -> None:
    node = document
    for key in path[:-1]:
        child = node.get(key)
        if not isinstance(child, dict):
            child = {}
            node[key] = child
        node = child
    node[path[-1]] = value


def merge_v2(current: Mapping[str, Any], template: Mapping[str, Any]) -> dict[str, Any]:
    """groovyscope's V2 entries win for scalars; for the list keys the user's own entries are kept."""
    merged = deep_merge(current, template)
    for path in UNION_PATHS:
        existing = get_path(current, path)
        if existing is not None:
            if path == ("allowedTools",) and isinstance(existing, list):
                existing = [entry for entry in existing if entry not in RETIRED_ALLOWED_TOOLS]
            if path == ("toolsSettings", "shell", "allowedCommands") and isinstance(existing, list):
                existing = [entry for entry in existing if entry not in RETIRED_SHELL_COMMANDS]
            set_path(merged, path, union(existing, get_path(template, path)))
    return merged


def merge_v3(current: Mapping[str, Any], template: Mapping[str, Any] | None = None) -> dict[str, Any]:
    """A V3-only agent keeps its format; groovyscope's permission rules are added when missing, and the keys both
    engines read (hooks, welcome message, resources) are merged from ``template`` with the user's entries kept."""
    merged: dict[str, Any] = json.loads(json.dumps(current))
    if template is not None:
        shared = {key: value for key, value in template.items() if key in SHARED_KEYS}
        merged = merge_v2(merged, shared)
    permissions = merged.get("permissions")
    if not isinstance(permissions, dict):
        permissions = {}
        merged["permissions"] = permissions
    rules = permissions.get("rules")
    if not isinstance(rules, list):
        rules = []
    rules = [rule for rule in rules if rule not in RETIRED_V3_RULES]
    permissions["rules"] = rules
    for rule in v3_rules():
        if rule not in rules:
            rules.append(dict(rule))
    return merged


def v2_view(agent: Mapping[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in agent.items() if key not in V3_KEYS}


def dump(agent: Mapping[str, Any]) -> str:
    return json.dumps(agent, indent=2, ensure_ascii=False) + "\n"


def target_format(requested: str, existing: str | None, can_upgrade: bool) -> str:
    """What to write: the requested format, or with ``auto`` the existing file's format (a new file is universal
    when kiro-cli can produce it, else V2)."""
    if requested != "auto":
        return requested
    if existing is None:
        return "universal" if can_upgrade else "v2"
    return {"universal-out-of-sync": "universal"}.get(existing, existing)


def merge_agent(existing: str | None, template: str, *, requested: str, run: KiroRunner | None) -> AgentMerge:
    """Merge groovyscope's agent template into an existing agent file, keeping (or converting to) a format."""
    ours = json.loads(template)
    current_format = classify(existing, run) if existing is not None else None
    if current_format in {"unknown", "external"} and requested == "auto":
        reason = "managed outside Kiro's project files" if current_format == "external" else "a newer, unknown format"
        return {"text": existing or "", "format": current_format, "note": f"agent is {reason}; left unchanged"}
    current: dict[str, Any] = json.loads(existing) if existing is not None else {}
    can_upgrade = supports_upgrade(run)
    goal = target_format(requested, current_format, can_upgrade)
    if goal == "v3":
        if current_format is None:
            merged = merge_v3({key: value for key, value in ours.items() if key not in V2_KEYS})
        else:
            merged = merge_v3({key: value for key, value in current.items() if key not in ("kiroUpgrade",)}, ours)
        return {"text": dump(merged), "format": "v3", "note": ""}
    v2 = merge_v2(v2_view(current), ours)
    if goal == "v2":
        note = "" if current_format in {None, "v2"} else f"converted from {current_format} to v2"
        return {"text": dump(v2), "format": "v2", "note": note}
    if current_format == "universal" and v2_view(current) == v2 and existing is not None:
        return {"text": existing, "format": "universal", "note": ""}
    return to_universal(dump(v2), existing if current_format == "universal" else None, run)


def to_universal(v2_text: str, universal_existing: str | None, run: KiroRunner | None) -> AgentMerge:
    """The universal form of a merged V2 agent through kiro-cli; without kiro-cli, an existing universal file is
    left as it is (writing the V2 view would drop its permissions block and stamp, and the V3 engine would lose the
    agent), and a new file is written as V2."""
    upgraded = upgrade(v2_text, run)
    if upgraded is not None:
        return {"text": upgraded, "format": "universal", "note": ""}
    if universal_existing is not None:
        return {
            "text": universal_existing,
            "format": "universal",
            "note": "agent left unchanged: kiro-cli is needed to update a universal agent file; run "
            "`groovyscope kiro repair --global` in a terminal with kiro-cli on PATH",
        }
    return {
        "text": v2_text,
        "format": "v2",
        "note": "kiro-cli cannot write the universal format here; wrote v2 (loaded by the default V2 engine)",
    }


def mcp_servers(files: Sequence[Path]) -> list[str]:
    """Names of the enabled MCP servers in Kiro ``mcp.json`` files (later files add to earlier ones)."""
    names: list[str] = []
    for path in files:
        try:
            servers = json.loads(path.read_text(encoding="utf-8")).get("mcpServers", {})
        except (OSError, ValueError, AttributeError):
            continue
        if isinstance(servers, dict):
            for name, server in servers.items():
                enabled = not (isinstance(server, dict) and server.get("disabled") is True)
                if enabled and name not in names:
                    names.append(name)
    return names


def hidden_servers(agent: Mapping[str, Any], servers: Sequence[str]) -> list[str]:
    """MCP servers whose tools the agent's V2 ``tools`` list does not expose (``*`` or ``@name`` exposes them).
    A V3-only agent selects tools differently, so nothing is reported for it."""
    tools = agent.get("tools")
    if not isinstance(tools, list) or "*" in tools:
        return []
    listed = {str(tool) for tool in tools}
    return [name for name in servers if f"@{name}" not in listed and not any(t.startswith(f"@{name}/") for t in listed)]
