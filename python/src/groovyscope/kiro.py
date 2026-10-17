"""Idempotent writers for the Kiro CLI files: merge JSON, never clobber, back up originals, show diffs."""

from __future__ import annotations

import difflib
import json
from collections.abc import Sequence
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
BACKUP_SUFFIX = ".bak"
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


def merge_content(existing: str | None, template: str, *, is_json: bool, force: bool, path: str) -> str:
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
    merged = deep_merge(current, json.loads(template))
    return json.dumps(merged, indent=2, ensure_ascii=False) + "\n"


def plan_changes(root: Path, *, force: bool = False) -> list[KiroChange]:
    """Compute every write without touching the file system."""
    changes: list[KiroChange] = []
    for template_name, relative in KIRO_FILES:
        destination = root / relative
        existing = destination.read_text(encoding="utf-8") if destination.is_file() else None
        after = merge_content(
            existing, load_template(template_name), is_json=relative.endswith(".json"), force=force, path=relative
        )
        if existing is None:
            action = "create"
        elif existing == after:
            action = "unchanged"
        else:
            action = "update"
        changes.append(
            {"template": template_name, "path": relative, "action": action, "before": existing, "after": after}
        )
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
