"""Kiro CLI hooks: what ``groovyscope hook`` does with the JSON Kiro hands it on stdin.

Two events matter. ``PostToolUse`` after a ``write``: the Groovy file the agent just wrote is checked and the findings
go back into the same turn, so a broken edit is seen at once instead of at the end of the task. ``Stop``, opt in:
the turn is refused while the Groovy files changed in the working tree have compile errors. Both are settings in
``.groovyscope.yaml`` (``hooks.*``). Everything here is pure; the CLI wires the engine and git in.
"""

from __future__ import annotations

import json
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any, TypedDict

from groovyscope import config as config_module

# Tool ids Kiro uses for file writes, in V2 and V3 catalogues; anything else is not a write.
WRITE_TOOLS = frozenset({"write", "fs_write", "write_file", "str_replace", "fs_append", "append"})
DELETE_TOOLS = frozenset({"delete_file"})
PATH_KEYS = ("path", "paths", "file_path", "filePath", "file")
EVENT_NAMES = {
    "posttooluse": "postToolUse",
    "stop": "stop",
    "sessionstart": "sessionStart",
    "agentspawn": "sessionStart",
    "userpromptsubmit": "userPromptSubmit",
    "pretooluse": "preToolUse",
    "sessionend": "sessionEnd",
}
GROOVY_SUFFIXES = (".groovy", ".gvy", ".gy", ".gsh", ".gradle")
ERROR_LEVEL = 1
PORCELAIN_PATH_OFFSET = 3  # `XY path`: two status letters and a space
WARNING_LEVEL = 2
Runner = Callable[[Sequence[str], Path], tuple[int, str]]


class HookSettings(TypedDict):
    """``hooks`` from the effective configuration."""

    afterWrite: bool
    afterWriteInclude: list[str]
    afterWriteMaxFindings: int
    stopGate: bool
    timeoutSeconds: int


def settings(effective: Mapping[str, Any]) -> HookSettings:
    """The ``hooks`` section with the defaults filled in."""
    defaults = config_module.load_defaults().get("hooks", {})
    section: Mapping[str, Any] = effective.get("hooks") or {}
    return {
        "afterWrite": bool(section.get("afterWrite", defaults.get("afterWrite", True))),
        "afterWriteInclude": [str(x) for x in section.get("afterWriteInclude", defaults.get("afterWriteInclude", []))],
        "afterWriteMaxFindings": int(section.get("afterWriteMaxFindings", defaults.get("afterWriteMaxFindings", 12))),
        "stopGate": bool(section.get("stopGate", defaults.get("stopGate", False))),
        "timeoutSeconds": int(section.get("timeoutSeconds", defaults.get("timeoutSeconds", 45))),
    }


def parse_payload(text: str) -> dict[str, Any]:
    """The hook payload Kiro writes to stdin; an empty mapping for anything that is not a JSON object."""
    try:
        loaded = json.loads(text) if text.strip() else {}
    except json.JSONDecodeError:
        return {}
    return loaded if isinstance(loaded, dict) else {}


def event_name(payload: Mapping[str, Any], override: str | None = None) -> str:
    """The event in groovyscope's spelling (``postToolUse``, ``stop``, ...), whatever casing Kiro 2.x or V3 used."""
    raw = override or payload.get("hook_event_name") or payload.get("event") or ""
    return EVENT_NAMES.get(str(raw).replace("_", "").replace("-", "").lower(), str(raw))


def is_groovy_source(path: Path) -> bool:
    return path.suffix in GROOVY_SUFFIXES or path.name.startswith("Jenkinsfile")


def written_paths(payload: Mapping[str, Any], root: Path) -> list[Path]:
    """The Groovy files a write tool call touched, inside ``root`` and still present; empty for any other tool."""
    tool = str(payload.get("tool_name", "")).split("/")[-1].lower()
    if tool not in WRITE_TOOLS or tool in DELETE_TOOLS:
        return []
    tool_input = payload.get("tool_input")
    if not isinstance(tool_input, Mapping):
        return []
    candidates: list[str] = []
    for key in PATH_KEYS:
        value = tool_input.get(key)
        if isinstance(value, str):
            candidates.append(value)
        elif isinstance(value, list):
            candidates.extend(str(item) for item in value)
    found: list[Path] = []
    resolved_root = root.resolve()
    for candidate in candidates:
        path = Path(candidate)
        if not path.is_absolute():
            path = root / path
        path = path.resolve()
        if not is_groovy_source(path) or not path.is_file():
            continue
        try:
            path.relative_to(resolved_root)
        except ValueError:
            continue
        if path not in found:
            found.append(path)
    return found


def render_after_write(rows: Sequence[Mapping[str, Any]], paths: Sequence[Path], root: Path, limit: int) -> str:
    """The block the agent reads after a write: one header line with the counts, then up to ``limit`` findings,
    worst first. Empty when the files are clean, so a clean write adds nothing to the context."""
    if not rows:
        return ""
    ordered = sorted(rows, key=lambda row: (int(row.get("level", 3)), str(row.get("path")), int(row.get("line", 0))))
    errors = sum(1 for row in ordered if int(row.get("level", 3)) <= ERROR_LEVEL)
    warnings = sum(1 for row in ordered if int(row.get("level", 3)) == WARNING_LEVEL)
    shown = ", ".join(_relative(path, root) for path in paths)
    lines = [f"groovyscope checked {shown}: {errors} error(s), {warnings} warning(s), {len(ordered)} finding(s)"]
    for row in ordered[:limit]:
        lines.append(
            f"  {row.get('path')}:{row.get('line')}:{row.get('column')} {row.get('severity')} "
            f"[{row.get('source')}/{row.get('code')}] {row.get('message')}"
        )
    if len(ordered) > limit:
        lines.append(f"  ... {len(ordered) - limit} more; run groovyscope.check on the file for all of them")
    if errors:
        lines.append("  fix the errors before moving on; `groovyscope.fix` applies the safe ones")
    return "\n".join(lines)


def changed_groovy_files(root: Path, runner: Runner) -> list[Path]:
    """Groovy files added or modified in the working tree (staged or not, untracked included), from git; empty
    when git is unavailable or the root is not a repository."""
    code, output = runner(["git", "status", "--porcelain", "-z", "--untracked-files=all", "--no-renames"], root)
    if code != 0:
        return []
    found: list[Path] = []
    # -z: entries end with NUL and paths are never quoted or escaped, so a non-ASCII name is read as it is.
    for entry in output.split("\0"):
        if len(entry) <= PORCELAIN_PATH_OFFSET or "D" in entry[:2]:
            continue
        path = root / entry[PORCELAIN_PATH_OFFSET:]
        if is_groovy_source(path) and path.is_file():
            found.append(path)
    return found


def stop_decision(rows: Sequence[Mapping[str, Any]], limit: int) -> dict[str, str] | None:
    """The ``Stop`` answer: block with the compile errors while changed files do not compile, else nothing."""
    errors = [row for row in rows if int(row.get("level", 3)) <= ERROR_LEVEL]
    if not errors:
        return None
    listed = "; ".join(f"{row.get('path')}:{row.get('line')} {row.get('message')}" for row in errors[:limit])
    more = f" (+{len(errors) - limit} more)" if len(errors) > limit else ""
    return {
        "decision": "block",
        "reason": f"groovyscope: {len(errors)} compile error(s) in the files you changed{more}: {listed}. "
        "Fix them before finishing.",
    }


def _relative(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return path.as_posix()
