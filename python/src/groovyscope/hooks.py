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

from groovyscope import atomic, conflicts, unused
from groovyscope import config as config_module
from groovyscope.paths import GROOVY_SUFFIXES

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
ERROR_LEVEL = 1
PORCELAIN_PATH_OFFSET = 3  # `XY path`: two status letters and a space
WARNING_LEVEL = 2
Runner = Callable[[Sequence[str], Path], tuple[int, str]]


class WriteIntent(TypedDict):
    """A write Kiro is about to make: the file, the text before, and the text after (None when it cannot be told,
    with the reason)."""

    path: Path
    command: str
    before: str
    after: str | None
    old_str: str
    new_str: str
    reason: str


class HookSettings(TypedDict):
    """``hooks`` from the effective configuration."""

    afterWrite: bool
    afterWriteInclude: list[str]
    afterWriteMaxFindings: int
    stopGate: bool
    timeoutSeconds: int


def settings(effective: Mapping[str, Any]) -> HookSettings:
    """The ``hooks`` section with the defaults filled in."""
    section: Mapping[str, Any] = effective.get("hooks") or {}

    def value(key: str) -> Any:
        return section.get(key, config_module.default("hooks", key))

    return {
        "afterWrite": bool(value("afterWrite")),
        "afterWriteInclude": [str(x) for x in value("afterWriteInclude")],
        "afterWriteMaxFindings": int(value("afterWriteMaxFindings")),
        "stopGate": bool(value("stopGate")),
        "timeoutSeconds": int(value("timeoutSeconds")),
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


def capture_name(event: str, stamp_ns: int) -> str:
    """``<event>-<timestamp>.json`` for a captured payload; the event is reduced to letters and digits."""
    safe = "".join(ch for ch in event if ch.isalnum()) or "unknown"
    return f"{safe}-{stamp_ns}.json"


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


def write_intent(payload: Mapping[str, Any], root: Path) -> WriteIntent | None:
    """The Groovy file a write tool call is about to change inside ``root``, with the text it would hold after the
    call, rebuilt from ``tool_input`` (``create`` gives the whole text; ``str_replace``, ``insert`` and ``append``
    are applied to the file on disk); None for any other tool or file."""
    tool = str(payload.get("tool_name", "")).split("/")[-1].lower()
    tool_input = payload.get("tool_input")
    if tool not in WRITE_TOOLS or not isinstance(tool_input, Mapping):
        return None
    raw = next((tool_input[k] for k in PATH_KEYS if isinstance(tool_input.get(k), str)), None)
    if raw is None:
        return None
    path = Path(raw) if Path(raw).is_absolute() else root / raw
    path = path.resolve()
    if not is_groovy_source(path) or not path.is_relative_to(root.resolve()) or path.is_dir():
        return None
    try:
        before = path.read_text(encoding="utf-8") if path.is_file() else ""
    except (OSError, UnicodeDecodeError):
        return None
    command = str(tool_input.get("command") or ("create" if _whole_text(tool_input) is not None else ""))
    old, new = str(tool_input.get("old_str") or ""), str(tool_input.get("new_str") or "")
    after, reason = _after(command, before, tool_input, old, new)
    return {
        "path": path,
        "command": command,
        "before": before,
        "after": after,
        "old_str": old,
        "new_str": new,
        "reason": reason,
    }


def _whole_text(tool_input: Mapping[str, Any]) -> str | None:
    for key in ("file_text", "content", "text"):
        if isinstance(tool_input.get(key), str):
            return str(tool_input[key])
    return None


Rebuilt = tuple[str | None, str]


def _after(command: str, before: str, tool_input: Mapping[str, Any], old: str, new: str) -> Rebuilt:
    """The text after a write command, or None with the reason it cannot be rebuilt."""
    handler = REBUILDERS.get(command)
    if handler is None:
        return (None, f"unknown write command {command or '(none)'}")
    return handler(before, tool_input, old, new)


def _create(_before: str, tool_input: Mapping[str, Any], _old: str, _new: str) -> Rebuilt:
    whole = _whole_text(tool_input)
    return (whole, "") if whole is not None else (None, "create without a file_text")


def _replace(before: str, _tool_input: Mapping[str, Any], old: str, new: str) -> Rebuilt:
    count = before.count(old) if old else 0
    return (before.replace(old, new, 1), "") if count == 1 else (None, f"old_str occurs {count} times in the file")


def _insert(before: str, tool_input: Mapping[str, Any], _old: str, new: str) -> Rebuilt:
    try:
        at = int(tool_input.get("insert_line", 0))
    except (TypeError, ValueError):
        return (None, "insert without a numeric insert_line")
    lines = before.splitlines(keepends=True)
    added = new if new.endswith("\n") else new + "\n"
    if at > 0 and lines and not lines[min(at, len(lines)) - 1].endswith("\n"):
        added = "\n" + added
    return ("".join([*lines[:at], added, *lines[at:]]), "")


def _append(before: str, _tool_input: Mapping[str, Any], _old: str, new: str) -> Rebuilt:
    joiner = "" if not before or before.endswith("\n") else "\n"
    return (before + joiner + new, "")


REBUILDERS: dict[str, Callable[[str, Mapping[str, Any], str, str], Rebuilt]] = {
    "create": _create,
    "str_replace": _replace,
    "insert": _insert,
    "append": _append,
}


def prompt_text(payload: Mapping[str, Any]) -> str:
    """The user's prompt in a UserPromptSubmit payload, whatever key this Kiro uses."""
    for key in ("prompt", "user_prompt", "message", "text"):
        if isinstance(payload.get(key), str):
            return str(payload[key])
    return ""


GROOVY_WORDS = ("groovy", "gradle", "jenkinsfile", "spock")


def about_groovy(prompt: str) -> bool:
    lowered = prompt.lower()
    return any(word in lowered for word in GROOVY_WORDS)


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
    dead = unused.summary(ordered, limit=limit)
    if dead["total"]:
        where = ", ".join(f"{item['line']} {item['kind'].rstrip('s')}" for item in dead["items"])
        lines.append(f"  UNUSED: {unused.counted(dead['byKind'])} (lines {where}); remove them now")
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


def split_conflicted(paths: Sequence[Path], root: Path) -> tuple[list[Path], list[str]]:
    """The files still worth a check, and one line for each file that holds conflict markers: such a file cannot
    compile, so checking it only costs the agent seconds per edit of a merge in progress."""
    clean: list[Path] = []
    notes: list[str] = []
    for path in paths:
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if not conflicts.has_markers(text):
            clean.append(path)
            continue
        parsed = conflicts.parse(text)
        where = ", ".join(str(hunk["start"] + 1) for hunk in parsed["hunks"][:8])
        note = f"groovyscope: {_relative(path, root)} still has {len(parsed['hunks'])} conflict hunk(s)"
        note += f" (at lines {where})" if where else ""
        if parsed["leftovers"]:
            stray = ", ".join(str(item["line"]) for item in parsed["leftovers"][:8])
            note += f" and stray markers at lines {stray} left by an edit of part of a hunk"
        notes.append(note + "; not checked until they are gone. resolve_conflicts resolves hunks in one write.")
    return clean, notes


def conflict_decision(notes: Sequence[str]) -> dict[str, str] | None:
    """The ``Stop`` answer while changed files still hold conflict markers."""
    if not notes:
        return None
    return {"decision": "block", "reason": " ".join(notes) + " Resolve them before finishing."}


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


def file_stamps(paths: Sequence[Path]) -> dict[str, list[int]]:
    """``{path: [mtime_ns, size]}`` from stat alone: no file is read."""
    stamps: dict[str, list[int]] = {}
    for path in paths:
        try:
            info = path.stat()
        except OSError:
            continue
        stamps[str(path)] = [info.st_mtime_ns, info.st_size]
    return stamps


def record_baseline(root: Path, runner: Runner, target: Path) -> int:
    """At session start: the Groovy files already changed in the working tree, so the stop gate holds the agent to
    the files it changes, not to someone's earlier work in progress. Returns how many were recorded."""
    stamps = file_stamps(changed_groovy_files(root, runner))
    atomic.write_text(target, json.dumps(stamps, sort_keys=True) + "\n")
    return len(stamps)


def changed_since_baseline(changed: Sequence[Path], target: Path) -> list[Path]:
    """``changed`` without the files that were already changed at session start and have not been touched since;
    all of ``changed`` when no baseline was recorded (or it cannot be read)."""
    try:
        baseline = json.loads(target.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return list(changed)
    if not isinstance(baseline, dict):
        return list(changed)
    now = file_stamps(changed)
    return [path for path in changed if baseline.get(str(path)) != now.get(str(path))]


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
