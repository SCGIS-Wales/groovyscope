"""Idempotent writers for the Kiro CLI files: merge JSON, never clobber, back up originals, show diffs."""

from __future__ import annotations

import difflib
import json
import os
import re
import shutil
import tempfile
from collections.abc import Mapping, Sequence
from importlib import resources
from pathlib import Path
from typing import Any, NotRequired, TypedDict

from groovyscope import __version__, kiro_agent
from groovyscope.config import deep_merge

# (template name inside the package, destination relative to the workspace root)
KIRO_FILES: tuple[tuple[str, str], ...] = (
    ("lsp.json", ".kiro/settings/lsp.json"),
    ("mcp.json", ".kiro/settings/mcp.json"),
    ("agents/groovy.json", ".kiro/agents/groovy.json"),
    ("agents/groovy-reviewer.json", ".kiro/agents/groovy-reviewer.json"),
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
# The steering template carries this token; the written file carries the package version instead, so `checkup`
# can tell Kiro files written by an older groovyscope from current ones.
STAMP_PLACEHOLDER = "{{version}}"
STAMP_PATTERN = re.compile(r"<!-- written by groovyscope (?P<version>[^\s;]+)")
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
    note: NotRequired[str]


def load_template(name: str) -> str:
    """A template with the version token replaced, so the written file names the groovyscope that wrote it."""
    text = resources.files("groovyscope").joinpath("_templates/kiro").joinpath(name).read_text(encoding="utf-8")
    return text.replace(STAMP_PLACEHOLDER, __version__)


def written_by(text: str | None) -> str | None:
    """The groovyscope version stamped into a steering file, or None for a file without a stamp."""
    match = STAMP_PATTERN.search(text or "")
    return match.group("version") if match else None


def kiro_files_version(home: Path) -> str | None:
    """Which groovyscope wrote the global Kiro files (from the steering stamp); None when unstamped or absent."""
    try:
        return written_by((home / "steering" / "groovyscope.md").read_text(encoding="utf-8"))
    except OSError:
        return None


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


def planned(
    base: Path,
    template_name: str,
    relative: str,
    template: str,
    *,
    force: bool,
    agent_format: str = "auto",
    run: kiro_agent.KiroRunner | None = None,
) -> KiroChange:
    destination = base / relative
    existing = destination.read_text(encoding="utf-8") if destination.is_file() else None
    note = ""
    if template_name == "agents/groovy.json":
        current = None if force else retire_keys(existing, template_name, relative)
        merged = kiro_agent.merge_agent(current, template, requested=agent_format, run=run)
        after, note = merged["text"], merged["note"]
    else:
        after = merge_content(
            existing,
            template,
            is_json=relative.endswith(".json"),
            force=force,
            path=relative,
            template_name=template_name,
        )
    action = "create" if existing is None else "unchanged" if existing == after else "update"
    change: KiroChange = {
        "template": template_name,
        "path": relative,
        "action": action,
        "before": existing,
        "after": after,
    }
    if note:
        change["note"] = note
    return change


def retire_keys(existing: str | None, template_name: str, path: str) -> str | None:
    """The existing JSON with keys earlier groovyscope releases wrote removed (see RETIRED_KEYS)."""
    if existing is None:
        return None
    try:
        current: Any = json.loads(existing)
    except json.JSONDecodeError as exc:
        msg = f"{path} is not valid JSON ({exc.msg} at line {exc.lineno}); fix it or rerun with --force"
        raise ValueError(msg) from exc
    if not isinstance(current, dict):
        msg = f"{path} must contain a JSON object; rerun with --force to replace it"
        raise ValueError(msg)
    retired = {key for key, value in RETIRED_KEYS.get(template_name, {}).items() if current.get(key) == value}
    if not retired:
        return existing
    return json.dumps({k: v for k, v in current.items() if k not in retired}, indent=2, ensure_ascii=False) + "\n"


def plan_changes(
    base: Path,
    *,
    force: bool = False,
    global_scope: bool = False,
    profile_guidance: str = "",
    lsp_only: bool = False,
    agent_format: str = "auto",
    run: kiro_agent.KiroRunner | None = None,
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
            agent_format=agent_format,
            run=run,
        )
        for name, relative in files
    ]
    if profile_guidance and not lsp_only:
        relative = PROFILE_STEERING if global_scope else ".kiro/" + PROFILE_STEERING
        changes.append(planned(base, PROFILE_STEERING, relative, STEERING_HEADER + profile_guidance, force=force))
    return changes


NO_NEWLINE = "\\ No newline at end of file\n"


def text_diff(before: str | None, after: str, label: str) -> str:
    """A unified diff that marks a missing final newline the way `diff` does, instead of gluing the last line of
    one side to the next line of the output."""
    lines: list[str] = []
    for line in difflib.unified_diff(
        (before or "").splitlines(keepends=True),
        after.splitlines(keepends=True),
        fromfile=f"a/{label}",
        tofile=f"b/{label}",
    ):
        lines.append(line if line.endswith("\n") else line + "\n" + NO_NEWLINE)
    return "".join(lines)


def unified_diff(change: KiroChange) -> str:
    return text_diff(change["before"], change["after"], change["path"])


def write_atomically(destination: Path, text: str) -> None:
    """Write via a temporary file in the same folder and an atomic rename, so a crash never leaves half a file."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    # A unique name per writer: two sessions repairing the same file at once never rename each other's half.
    with tempfile.NamedTemporaryFile(
        "w",
        encoding="utf-8",
        dir=destination.parent,
        prefix=f".{destination.name}.",
        suffix=".groovyscope-tmp",
        delete=False,
    ) as handle:
        handle.write(text)
        temporary = Path(handle.name)
    try:
        if destination.exists():
            shutil.copymode(destination, temporary)
        else:
            temporary.chmod(0o666 & ~current_umask())
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def current_umask() -> int:
    """The process umask (os.umask can only be read by setting it)."""
    mask = os.umask(0)
    os.umask(mask)
    return mask


def backup_path(destination: Path) -> Path:
    """``<name>.bak``, or ``<name>.bak.N`` when earlier backups exist, so no backup is ever overwritten."""
    candidate = destination.with_name(destination.name + BACKUP_SUFFIX)
    number = 1
    while candidate.exists():
        candidate = destination.with_name(f"{destination.name}{BACKUP_SUFFIX}.{number}")
        number += 1
    return candidate


def apply_changes(root: Path, changes: Sequence[KiroChange], *, backup: bool = True) -> list[Path]:
    """Write every create/update atomically, backing up overwritten files first. Returns written paths."""
    written: list[Path] = []
    for change in changes:
        if change["action"] == "unchanged":
            continue
        destination = root / change["path"]
        if backup and change["before"] is not None:
            write_atomically(backup_path(destination), change["before"])
        write_atomically(destination, change["after"])
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
