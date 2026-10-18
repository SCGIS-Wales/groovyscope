"""The MCP tool catalogue: one place that says which tools exist and which of them change the workspace.

Kiro CLI does not honour MCP's ``readOnlyHint`` annotation, so the agent files groovyscope writes derive their
approval rules from this catalogue instead: read-only tools run without a prompt, the others ask. The MCP server
reads the same table for its tool titles and annotations, so the two can never disagree.
"""

from __future__ import annotations

SERVER_NAME = "groovyscope"

# name: (title, read only). Every tool is closed-world (only this workspace) and idempotent.
TOOL_INFO: dict[str, tuple[str, bool]] = {
    "find_symbol": ("Find Groovy declarations", True),
    "goto_definition": ("Go to definition", True),
    "type_definition": ("Go to type definition", True),
    "find_references": ("Find references", True),
    "hover": ("Hover information", True),
    "document_symbols": ("Document outline", True),
    "call_graph": ("Call graph", True),
    "impact_of": ("Impact of a change", True),
    "check": ("Check findings", True),
    "review": ("Review changed files", True),
    "explain": ("Explain a finding code", True),
    "profile_status": ("Runtime profile of a file", True),
    "find_dynamic_code": ("Find dynamic Groovy", True),
    "workspace_status": ("Workspace and daemon status", True),
    "refresh_classpath": ("Refresh the classpath from the build", False),
    "format": ("Format files", False),
    "fix": ("Apply safe fixes", False),
    "rename": ("Rename a symbol", False),
    "read_symbol": ("Read one declaration", True),
    "find_code": ("Structural code search", True),
    "find_duplicates": ("Find duplicated code", True),
    "workspace_overview": ("Workspace overview", True),
    "review_changes": ("Review the branch as a merge request", True),
    "coverage_gaps": ("Uncovered classes and methods", True),
    "explain_code": ("Explain what code does", True),
    "relationship_graph": ("Class relationship graph", False),
    "check_naming": ("Naming findings and facts", True),
}


def read_only_tools() -> tuple[str, ...]:
    """The tools that never write to the workspace, in catalogue order."""
    return tuple(name for name, (_, read_only) in TOOL_INFO.items() if read_only)


def mutating_tools() -> tuple[str, ...]:
    """The tools that can write files or run the build (``apply`` edits, the classpath export, a draw.io file)."""
    return tuple(name for name, (_, read_only) in TOOL_INFO.items() if not read_only)


def v2_allowed_entries() -> tuple[str, ...]:
    """``allowedTools`` entries for a Kiro 2.x agent: one ``@groovyscope/<tool>`` per read-only tool."""
    return tuple(f"@{SERVER_NAME}/{name}" for name in read_only_tools())


def v3_matches(names: tuple[str, ...]) -> list[str]:
    """``permissions.rules`` ``match`` entries for the V3 engine: ``groovyscope/<tool>``."""
    return [f"{SERVER_NAME}/{name}" for name in names]
