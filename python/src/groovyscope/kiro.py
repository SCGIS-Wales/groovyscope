"""Idempotent writers for the Kiro CLI files: merge JSON, never clobber, back up originals, show diffs."""

from __future__ import annotations

import difflib
import json
from collections.abc import Mapping, Sequence
from importlib import resources
from pathlib import Path
from typing import Any, TypedDict

from groovyscope.config import deep_merge

# (template name inside the package, destination relative to the workspace root)
KIRO_FILES: tuple[tuple[str, str], ...] = (
    ("lsp.json", ".kiro/settings/lsp.json"),
    ("mcp.json", ".kiro/settings/mcp.json"),
    ("agents/groovy.json", ".kiro/agents/groovy.json"),
    ("steering/groovyscope.md", ".kiro/steering/groovyscope.md"),
)
# Files relative to the Kiro home (~/.kiro, or $KIRO_HOME) for `kiro init --global`. Kiro CLI 2.x reads LSP servers
# only from the project's .kiro/settings/lsp.json, so the global setup has no lsp.json.
GLOBAL_FILES: tuple[tuple[str, str], ...] = tuple(
    (name, path.removeprefix(".kiro/")) for name, path in KIRO_FILES if name != "lsp.json"
)
LSP_ONLY_FILES: tuple[tuple[str, str], ...] = tuple((name, path) for name, path in KIRO_FILES if name == "lsp.json")
PROFILE_STEERING = "steering/groovyscope-profiles.md"
STEERING_HEADER = "---\ninclusion: always\n---\n"
KIRO_HOME_ENV = "KIRO_HOME"
BACKUP_SUFFIX = ".bak"
# Keys earlier groovyscope releases wrote that current Kiro CLI misreads, removed on merge only while they still
# hold exactly what groovyscope wrote. A `permissions` block makes Kiro CLI 2.x treat the agent as V3-only and
# ignore it ("agent groovy not found"); the agent now uses allowedTools and toolsSettings instead.
RETIRED_KEYS: dict[str, dict[str, Any]] = {
    "agents/groovy.json": {
        "permissions": {
            "rules": [
                {"capability": "mcp", "match": ["groovyscope/*"], "effect": "allow"},
                {
                    "capability": "shell",
                    "match": ["./gradlew *", "groovyscope *", "git status", "git diff *"],
                    "effect": "allow",
                },
                {
                    "capability": "shell",
                    "match": ["grep *", "rg *", "sed *", "awk *", "head *", "tail *", "find * -name *.groovy*"],
                    "effect": "ask",
                },
            ]
        },
    },
}
KIRO_INIT_WARNING = (
    "Kiro's `/code init` can overwrite custom entries in .kiro/settings/lsp.json (kirodotdev/Kiro#10684). "
    "Commit the file and run `groovyscope kiro repair` if the groovy entry disappears."
)


class KiroChange(TypedDict):
    """One planned write: what exists now and what it will become."""

    template: str
    path: str
    action: str  # create | update | unchanged
    before: str | None
    after: str


def load_template(name: str) -> str:
    return resources.files("groovyscope").joinpath("_templates/kiro").joinpath(name).read_text(encoding="utf-8")


def normalize_json(text: str) -> str:
    return json.dumps(json.loads(text), indent=2, ensure_ascii=False) + "\n"


def kiro_home(environ: Mapping[str, str], home: Path) -> Path:
    """Where Kiro CLI keeps its global configuration: ``$KIRO_HOME`` when set, otherwise ``~/.kiro``."""
    explicit = environ.get(KIRO_HOME_ENV, "")
    return Path(explicit).expanduser() if explicit else home / ".kiro"


def global_template(name: str, base: Path) -> str:
    """A template adjusted for the Kiro home: the agent also reads the global steering files by absolute path."""
    text = load_template(name)
    if name != "agents/groovy.json":
        return text
    agent = json.loads(text)
    agent["resources"] = [*agent.get("resources", []), f"file://{base.as_posix()}/steering/**/*.md"]
    return json.dumps(agent, indent=2, ensure_ascii=False) + "\n"


def merge_content(
    existing: str | None, template: str, *, is_json: bool, force: bool, path: str, template_name: str = ""
) -> str:
    """Our entries win, everything else in an existing JSON file is preserved. Markdown is replaced."""
    if existing is None or force or not is_json:
        return normalize_json(template) if is_json else template
    try:
        current: Any = json.loads(existing)
    except json.JSONDecodeError as exc:
        msg = f"{path} is not valid JSON ({exc.msg} at line {exc.lineno}); fix it or rerun with --force"
        raise ValueError(msg) from exc
    if not isinstance(current, dict):
        msg = f"{path} must contain a JSON object; rerun with --force to replace it"
        raise ValueError(msg)
    for key, value in RETIRED_KEYS.get(template_name, {}).items():
        if current.get(key) == value:
            del current[key]
    merged = deep_merge(current, json.loads(template))
    return json.dumps(merged, indent=2, ensure_ascii=False) + "\n"


def planned(base: Path, template_name: str, relative: str, template: str, *, force: bool) -> KiroChange:
    destination = base / relative
    existing = destination.read_text(encoding="utf-8") if destination.is_file() else None
    after = merge_content(
        existing,
        template,
        is_json=relative.endswith(".json"),
        force=force,
        path=relative,
        template_name=template_name,
    )
    action = "create" if existing is None else "unchanged" if existing == after else "update"
    return {"template": template_name, "path": relative, "action": action, "before": existing, "after": after}


def plan_changes(
    base: Path,
    *,
    force: bool = False,
    global_scope: bool = False,
    profile_guidance: str = "",
    lsp_only: bool = False,
) -> list[KiroChange]:
    """Compute every write without touching the file system. ``base`` is the workspace root, or the Kiro home
    with ``global_scope``. ``lsp_only`` writes just the project's lsp.json (the rest comes from the global setup).
    Runtime profile guidance, when there is any, goes to its own steering file."""
    files = GLOBAL_FILES if global_scope else LSP_ONLY_FILES if lsp_only else KIRO_FILES
    changes = [
        planned(
            base,
            name,
            relative,
            global_template(name, base) if global_scope else load_template(name),
            force=force,
        )
        for name, relative in files
    ]
    if profile_guidance and not lsp_only:
        relative = PROFILE_STEERING if global_scope else ".kiro/" + PROFILE_STEERING
        changes.append(planned(base, PROFILE_STEERING, relative, STEERING_HEADER + profile_guidance, force=force))
    return changes


def unified_diff(change: KiroChange) -> str:
    before = (change["before"] or "").splitlines(keepends=True)
    after = change["after"].splitlines(keepends=True)
    label = change["path"]
    return "".join(difflib.unified_diff(before, after, fromfile=f"a/{label}", tofile=f"b/{label}"))


def apply_changes(root: Path, changes: Sequence[KiroChange], *, backup: bool = True) -> list[Path]:
    """Write every create/update, backing up overwritten files to ``<name>.bak``. Returns written paths."""
    written: list[Path] = []
    for change in changes:
        if change["action"] == "unchanged":
            continue
        destination = root / change["path"]
        destination.parent.mkdir(parents=True, exist_ok=True)
        if backup and change["before"] is not None:
            destination.with_name(destination.name + BACKUP_SUFFIX).write_text(change["before"], encoding="utf-8")
        destination.write_text(change["after"], encoding="utf-8")
        written.append(destination)
    return written


def present_files(root: Path) -> dict[str, bool]:
    """Which Kiro files exist under ``root`` (used by ``doctor``)."""
    return {relative: (root / relative).is_file() for _, relative in KIRO_FILES}


def has_global_setup(home: Path) -> bool:
    """Whether the Kiro home already carries groovyscope's MCP server and agent (``kiro init --global`` ran)."""
    try:
        servers = json.loads((home / "settings" / "mcp.json").read_text(encoding="utf-8")).get("mcpServers", {})
    except (OSError, ValueError, AttributeError):
        return False
    return isinstance(servers, dict) and "groovyscope" in servers and (home / "agents" / "groovy.json").is_file()
