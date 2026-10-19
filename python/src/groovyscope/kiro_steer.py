"""groovyscope in any Kiro agent, not only its own ``groovy`` agent.

An agent gets groovyscope by four parts: the MCP server (its own ``mcpServers`` entry, or the ``mcp.json`` files it
includes), the steering (``groovyscope.md`` in its ``resources``, without which it greps and reads whole files where
one groovyscope call answers), the read-only groovyscope tools in ``allowedTools`` (no prompt for each lookup), and
the hooks (the checkup at start, findings after each write and at the end of a turn). ``groovyscope kiro attach``
merges exactly these parts into the agents a user names, keeping everything else and the agent's format; ``doctor``
names the agents that call groovyscope with a part missing.
"""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

from groovyscope import kiro, kiro_agent

STEERING_FILE = "groovyscope.md"
WORKSPACE_STEERING = f"file://.kiro/steering/{STEERING_FILE}"
SERVER = "groovyscope"
TOOL_PREFIX = f"@{SERVER}"
HOOK_COMMANDS = ("groovyscope checkup --agent", "groovyscope hook")


def steering_resources(global_home: Path) -> list[str]:
    """The resources that load the steering: the project's copy (relative, so it follows the folder Kiro starts
    in) and the global copy by absolute path when ``kiro init --global`` wrote one."""
    resources = [WORKSPACE_STEERING]
    if (global_home / "steering" / STEERING_FILE).is_file():
        resources.append(f"file://{(global_home / 'steering' / STEERING_FILE).as_posix()}")
    return resources


def loads_steering(agent: Mapping[str, Any]) -> bool:
    """Whether the agent's resources include groovyscope's steering, by name or through a steering folder glob."""
    resources = agent.get("resources")
    if not isinstance(resources, list):
        return False
    return any(isinstance(item, str) and (STEERING_FILE in item or "/steering/*" in item) for item in resources)


def reaches_groovyscope(agent: Mapping[str, Any], configured: Sequence[str]) -> bool:
    """Whether the agent can call the groovyscope MCP tools: its own ``mcpServers`` names it, its tool lists name
    ``@groovyscope``, or it includes the ``mcp.json`` files (``configured``) that do."""
    servers = agent.get("mcpServers")
    if isinstance(servers, Mapping) and SERVER in servers:
        return True
    for key in ("tools", "allowedTools"):
        names = agent.get(key)
        if isinstance(names, list) and any(isinstance(n, str) and n.startswith(TOOL_PREFIX) for n in names):
            return True
    return agent.get("includeMcpJson") is True and SERVER in configured


def hook_commands(agent: Mapping[str, Any]) -> set[str]:
    """Every hook command the agent runs, whatever the event."""
    hooks = agent.get("hooks")
    if not isinstance(hooks, Mapping):
        return set()
    return {
        str(entry.get("command"))
        for entries in hooks.values()
        if isinstance(entries, list)
        for entry in entries
        if isinstance(entry, Mapping) and entry.get("command")
    }


def missing_parts(agent: Mapping[str, Any], configured: Sequence[str]) -> list[str]:
    """The groovyscope parts an agent lacks: ``server``, ``steering``, ``hooks``."""
    missing = [] if reaches_groovyscope(agent, configured) else ["server"]
    if not loads_steering(agent):
        missing.append("steering")
    if not set(HOOK_COMMANDS) <= hook_commands(agent):
        missing.append("hooks")
    return missing


def agent_files(root: Path, global_home: Path) -> list[Path]:
    """The project's and the global agent files, project first."""
    found: list[Path] = []
    for folder in (root / ".kiro" / "agents", global_home / "agents"):
        if folder.is_dir():
            found += sorted(folder.glob("*.json"))
    return found


def read_agent(path: Path) -> dict[str, Any] | None:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return document if isinstance(document, dict) else None


def agent_name(agent: Mapping[str, Any], path: Path) -> str:
    return str(agent.get("name") or path.stem)


def incomplete(files: Sequence[Path], configured: Sequence[str]) -> list[tuple[str, Path, list[str]]]:
    """``(name, file, missing parts)`` for every agent that can call groovyscope but lacks its steering or hooks."""
    found: list[tuple[str, Path, list[str]]] = []
    for path in files:
        agent = read_agent(path)
        if agent is None or not reaches_groovyscope(agent, configured):
            continue
        missing = missing_parts(agent, configured)
        if missing:
            found.append((agent_name(agent, path), path, missing))
    return found


def named(files: Sequence[Path], names: Sequence[str]) -> list[tuple[str, Path]]:
    """The agent files whose agent is called one of ``names`` (a name may live in the project and globally)."""
    chosen: list[tuple[str, Path]] = []
    for path in files:
        agent = read_agent(path)
        if agent is not None and agent_name(agent, path) in names:
            chosen.append((agent_name(agent, path), path))
    return chosen


def attachment(agent: Mapping[str, Any], configured: Sequence[str], global_home: Path) -> dict[str, Any]:
    """groovyscope's parts for one agent, from the ``groovy`` agent and ``mcp.json`` templates. The agent's own
    ``tools`` list gains ``@groovyscope`` only when it lists tools one by one; the server entry is added only when
    the agent cannot reach groovyscope already."""
    template = json.loads(kiro.load_template("agents/groovy.json"))
    parts: dict[str, Any] = {
        "allowedTools": [t for t in template.get("allowedTools", []) if str(t).startswith(TOOL_PREFIX)],
        "resources": steering_resources(global_home),
        "hooks": template.get("hooks", {}),
    }
    tools = agent.get("tools")
    if isinstance(tools, list) and "*" not in tools and TOOL_PREFIX not in tools:
        parts["tools"] = [TOOL_PREFIX]
    if not reaches_groovyscope(agent, configured):
        servers = json.loads(kiro.load_template("mcp.json"))["mcpServers"]
        parts["mcpServers"] = {SERVER: servers[SERVER]}
    return parts


def attached_text(existing: str, parts: Mapping[str, Any], run: kiro_agent.KiroRunner | None) -> kiro_agent.AgentMerge:
    """The agent file with groovyscope's parts merged in, in the file's own format. A V3 agent gains only
    groovyscope's MCP permission rules (never file or shell ones) and the server entry."""
    before = json.loads(existing)
    merged = kiro_agent.merge_agent(existing, kiro_agent.dump(parts), requested="auto", run=run)
    if merged["format"] != "v3":
        return merged
    document: dict[str, Any] = json.loads(merged["text"])
    old_rules = kiro_agent.get_path(before, ("permissions", "rules")) or []
    rules = kiro_agent.get_path(document, ("permissions", "rules")) or []
    document["permissions"]["rules"] = [
        rule for rule in rules if rule in old_rules or (isinstance(rule, Mapping) and rule.get("capability") == "mcp")
    ]
    if "mcpServers" in parts:
        document["mcpServers"] = {**(before.get("mcpServers") or {}), **parts["mcpServers"]}
    return {"text": kiro_agent.dump(document), "format": merged["format"], "note": merged["note"]}
