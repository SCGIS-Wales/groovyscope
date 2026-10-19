"""groovyscope's steering for Kiro agents other than its own.

A custom Kiro agent loads only the steering its ``resources`` list. An agent that can call the groovyscope MCP tools
but never loads ``groovyscope.md`` does not know to prefer ``read_symbol`` over grep and line ranges, or to review in
the repository it edits, so it spends credits finding code the tools return in one call. ``doctor`` names such agents
and ``groovyscope kiro steer`` adds the steering to them.
"""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

STEERING_FILE = "groovyscope.md"
WORKSPACE_STEERING = f"file://.kiro/steering/{STEERING_FILE}"
SERVER = "groovyscope"


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
        if isinstance(names, list) and any(isinstance(n, str) and n.startswith(f"@{SERVER}") for n in names):
            return True
    return agent.get("includeMcpJson") is True and SERVER in configured


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


def unsteered(files: Sequence[Path], configured: Sequence[str]) -> list[tuple[str, Path]]:
    """``(agent name, file)`` for every agent that can call groovyscope but does not load its steering."""
    gaps: list[tuple[str, Path]] = []
    for path in files:
        agent = read_agent(path)
        if agent is not None and reaches_groovyscope(agent, configured) and not loads_steering(agent):
            gaps.append((str(agent.get("name") or path.stem), path))
    return gaps


def with_steering(agent: Mapping[str, Any], resources: Sequence[str]) -> dict[str, Any]:
    """The agent with the steering resources appended (each once); everything else unchanged."""
    current = agent.get("resources")
    kept = [item for item in current if isinstance(item, str)] if isinstance(current, list) else []
    return {**agent, "resources": [*kept, *(item for item in resources if item not in kept)]}
