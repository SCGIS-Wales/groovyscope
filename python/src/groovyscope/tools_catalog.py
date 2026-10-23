"""The MCP tool catalogue: one place that says which tools exist, what each is for and which of them change the
workspace.

Kiro CLI does not honour MCP's ``readOnlyHint`` annotation, so the agent files groovyscope writes derive their
approval rules from this catalogue instead: read-only tools run without a prompt, the others ask. The MCP server
reads the same table for its tool titles, descriptions and annotations, so the two can never disagree.

Every tool schema is paid for in the agent's context on every session, so the descriptions here are one line:
what the tool answers and what its arguments mean. How to read an answer and which tool to call next live in
the skills and the steering file, loaded when they are needed, and in the answers themselves.
"""

from __future__ import annotations

from typing import TypedDict

SERVER_NAME = "groovyscope"

# name: (title, read only). The behaviour hints beyond read only are in ToolHints below.
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
    "find_unused": ("Find unused code", True),
    "workspace_status": ("Workspace and daemon status", True),
    "refresh_classpath": ("Refresh the classpath from the build", False),
    "format": ("Format files", False),
    "fix": ("Apply safe fixes", False),
    "rename": ("Rename a symbol", False),
    "edit_symbol": ("Edit one declaration, gated", False),
    "read_symbol": ("Read one declaration", True),
    "find_code": ("Structural code search", True),
    "find_duplicates": ("Find duplicated code", True),
    "workspace_overview": ("Workspace overview", True),
    "review_changes": ("Review the branch as a merge request", True),
    "coverage_gaps": ("Uncovered classes and methods", True),
    "explain_code": ("Explain what code does", True),
    "relationship_graph": ("Class relationship graph", False),
    "check_naming": ("Naming findings and facts", True),
    "symbol_context": ("Symbol context in one call", True),
    "test_results": ("Test results from JUnit XML", True),
    "test_plan": ("Tests to add, and only those", True),
    "merge_conflicts": ("Merge conflicts and their hunks", True),
    "resolve_conflicts": ("Resolve merge conflict hunks", False),
    "merge_review": ("Review a merge for lost code", True),
}

# What the agent reads for each tool. Arguments in capitals; PATH:LINE:COL is 1-based everywhere.
DESCRIPTIONS: dict[str, str] = {
    "find_symbol": "Declarations whose name contains QUERY (case-insensitive); KIND: class, method, field, variable.",
    "goto_definition": "The declaration under PATH:LINE:COL, with its confidence.",
    "type_definition": "The declaration of the type of the expression at PATH:LINE:COL.",
    "find_references": "Every use of the symbol under PATH:LINE:COL, each with a confidence; paged.",
    "hover": "Type, signature, documentation and finding notes at PATH:LINE:COL.",
    "document_symbols": "Outline of PATH with line ranges to DEPTH; filter by KINDS or NAME. Then read_symbol.",
    "call_graph": "Callers (DIRECTION in) or callees (out) of SYMBOL (pkg.Class.method, Class#method) to DEPTH.",
    "impact_of": "What breaks if SYMBOL changes (CHANGE: signature, rename, delete, behaviour): callers, tests, risk.",
    "check": "Findings for PATHS (all when empty), worst first; filter by CODES and MAX_PRIORITY (1 error, 3 info).",
    "review": "Findings for PATHS grouped by the review checklist with counts per code; MAX_PRIORITY as in check.",
    "explain": "What a finding CODE means, why it matters and how to fix it.",
    "profile_status": "The runtime profile governing PATH: target Groovy, construct status, protected parameters.",
    "find_dynamic_code": "Code that defeats static analysis: untyped classes, public def, methodMissing, metaClass.",
    "workspace_status": "Daemon, engine, trust and classpath state (fresh, stale, missing, configured, none).",
    "find_unused": "Unused imports, variables, parameters, private members, dead code in PATHS or CHANGED_ONLY lines.",
    "refresh_classpath": "Re-export the classpath from the Gradle or Maven build when stale; trusted workspaces only.",
    "format": "Formatting edits for PATHS (all when empty); APPLY writes them.",
    "fix": "Safe fixes for PATHS and CODES (all when empty); APPLY writes them.",
    "rename": "Rename the symbol at PATH:LINE:COL to NEW_NAME everywhere; dry run unless APPLY.",
    "edit_symbol": (
        "Edit SYMBOL in PATH by OPERATION (replace, replace_body, insert_before, insert_after, delete, add_member, "
        "add_import) with TEXT; gated on a preview. Dry run: diff, verdict, old_str/new_str for write. APPLY writes."
    ),
    "read_symbol": "Source of one class, method or field in PATH (SYMBOL: name or Class.name); LINE picks an overload.",
    "find_code": (
        "Structural search (not grep). KIND: catch, throw, call, new, return-null, string-literal, annotation, "
        "def, empty-block, field, method; narrow with NAME, TYPE, PATTERN, MODIFIERS, EMPTY_BODY, RETHROWS, "
        "MIN_LINES, MIN_PARAMETERS."
    ),
    "find_duplicates": "Groups of repeated code under PATHS, largest first: EXACT copies and SIMILAR shapes.",
    "workspace_overview": "Map of the codebase: packages, largest files and classes, untested classes. Call it first.",
    "review_changes": "The branch against BASE (or STAGED) as a merge request: findings, impact, verdict, comments.",
    "coverage_gaps": "Classes and methods no test executed, from the JaCoCo XML report, most missed first.",
    "explain_code": "Facts about what SYMBOL (Class, Class.method) or PATH does, from its tree and call graph.",
    "relationship_graph": (
        "Class relationships around SYMBOLS or PATHS to DEPTH; DRAWIO_PATH writes a draw.io file, MERMAID a diagram."
    ),
    "check_naming": "Naming findings GS020 to GS027 for PATHS, with each declaration's facts; rename via rename.",
    "symbol_context": "Definition, hover and reference count of the symbol at PATH:LINE:COL in one call.",
    "test_results": "Failing tests from TEST-*.xml at any depth under SOURCES (folders or .zip archives; default "
    "the workspace): message and the first frames in the code and the test.",
    "merge_conflicts": "Conflicted files of the merge in progress; with PATH its hunks, numbered, with suggestions.",
    "resolve_conflicts": (
        "Resolve PATH's hunks in one write: RESOLUTIONS [{hunk, take: ours|theirs|both|theirs-then-ours|base|neither}] "
        "or [{hunk, text}]; SUGGESTED for the rest; parsed first; APPLY writes."
    ),
    "merge_review": "Declarations a merge lost or changes it dropped, in Groovy files both sides changed (or PATHS).",
    "test_plan": (
        "For SYMBOL (Class.method) or every method the branch changed: the tests reaching it, repeated tests, and "
        "`needed`: the gaps to test, nothing else."
    ),
}


# Tools whose `apply` overwrites workspace files, so a client should confirm before calling them that way.
DESTRUCTIVE_TOOLS = frozenset({"edit_symbol", "fix", "format", "rename", "resolve_conflicts"})
# Repeating the call with the same arguments does not give the same result: after a rename the symbol at the
# position already carries the new name, and an inserted member is inserted again.
NOT_IDEMPOTENT_TOOLS = frozenset({"edit_symbol", "rename"})
# Reaches beyond this workspace: the classpath export runs the project's build, which may download dependencies.
OPEN_WORLD_TOOLS = frozenset({"refresh_classpath"})


class ToolHints(TypedDict):
    """The MCP behaviour annotations of one tool."""

    read_only: bool
    destructive: bool
    idempotent: bool
    open_world: bool


def hints(name: str) -> ToolHints:
    """The behaviour annotations of ``name``, from the catalogue (an unknown tool counts as read only)."""
    read_only = TOOL_INFO.get(name, (name, True))[1]
    return {
        "read_only": read_only,
        "destructive": not read_only and name in DESTRUCTIVE_TOOLS,
        "idempotent": name not in NOT_IDEMPOTENT_TOOLS,
        "open_world": name in OPEN_WORLD_TOOLS,
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


def description(name: str, fallback: str | None = None) -> str:
    """The one-line description the agent reads for ``name``."""
    return DESCRIPTIONS.get(name) or (fallback or name).strip()


# Shell commands an agent may run without asking (Kiro `toolsSettings.shell.allowedCommands`, matched whole). Only
# what reads, or runs the project's tests through Gradle (as the owner's plan allows): never `trust`, `classpath`
# (runs build scripts), `verify` (runs the build), `jdk`, `fetch`, `kiro`, `fix --apply`, `format --apply`,
# `checkup --fix`, `graph` writing a file, `doctor --bundle`, another Gradle task or flag, or `git diff --output`. A
# value may not start with `-` (so it is never an option) nor hold a shell metacharacter (`;`, `&`, `|`, `$`, a
# backquote, redirections, brackets) or a glob (`*`, `?`, `[`), which the shell would expand into file names chosen
# by the repository, such as one named `--apply`.
SHELL_VALUE = r"[A-Za-z0-9_@#%+=:,./][A-Za-z0-9_@#%+=:,./-]*"
SAFE_GROOVYSCOPE_OPTIONS: dict[str, tuple[str, ...]] = {
    "check": ("--profile V", "--include V", "--json", "--format (?:text|json|short|sarif)", "--import-sarif V"),
    "review": ("--changed", "--base V", "--staged", "--top V", "--json"),
    "test-results": ("--limit V", "--json"),
    "unused": ("--changed", "--base V", "--json"),
    "explain": ("--json",),
    "explain-code": ("--json",),
    "naming": ("--json",),
    "coverage": ("--json",),
    "impact": ("--change V", "--json"),
    "callgraph": ("--direction V", "--depth V", "--json"),
    # without a stdout format, graph writes a draw.io file: the format comes first
    "graph --format (?:mermaid|json)": ("--path V", "--kinds V", "--depth V", "--level V", "--max-nodes V"),
    "fix": ("--code V", "--changed", "--base V", "--json"),
    "format": ("--json",),
    "checkup": ("--agent", "--json"),
    "merge status": ("--json",),
    "merge show": ("--hunk V", "--json"),
    "merge review": ("--json",),
    "logs": ("-n V", "--lines V", "-f", "--follow", "--kiro"),
    "version": (),  # no options and no values
    "doctor": (),
    "daemon status": (),
}
SAFE_GRADLE_FLAGS = ("offline", "continue", "info", "quiet", "warn", "stacktrace", "rerun-tasks", "console=plain")
SAFE_OTHER_COMMANDS = (
    r"\./gradlew (?:test|check|jacocoTestReport)(?: --(?:" + "|".join(SAFE_GRADLE_FLAGS) + "))*",
    "git status",
    r"git diff(?: (?:--stat|--name-only|--name-status|--cached|--staged|--no-color|--numstat|--shortstat|--|"
    r"-U[0-9]+|--unified=[0-9]+|" + SHELL_VALUE + "))*",
)
# The same for the V3 engine (`permissions.rules` shell `match`, globs). Whether V3 splits `a; b` before matching is
# not confirmed, so these are exact commands with no wildcard; anything longer asks.
SAFE_SHELL_GLOBS = (
    "groovyscope check",
    "groovyscope check --json",
    "groovyscope review",
    "groovyscope review --changed",
    "groovyscope review --changed --json",
    "groovyscope test-results",
    "groovyscope unused",
    "groovyscope unused --changed",
    "groovyscope naming",
    "groovyscope coverage",
    "groovyscope version",
    "groovyscope doctor",
    "groovyscope checkup --agent",
    "groovyscope merge status",
    "groovyscope merge review",
    "groovyscope daemon status",
    "./gradlew test",
    "./gradlew check",
    "./gradlew jacocoTestReport",
    "git status",
    "git diff",
    "git diff --stat",
)


def safe_shell_commands() -> tuple[str, ...]:
    """The V2 ``allowedCommands`` regular expressions (see SAFE_GROOVYSCOPE_OPTIONS)."""
    patterns = []
    for command, options in SAFE_GROOVYSCOPE_OPTIONS.items():
        words = [option.replace(" V", " " + SHELL_VALUE) for option in options]
        if options:
            words.append(SHELL_VALUE)
        tail = f"(?: (?:{'|'.join(words)}))*" if words else ""
        patterns.append(f"groovyscope {command}{tail}")
    return (*patterns, *SAFE_OTHER_COMMANDS)
