"""Object oriented edits for the agent: change_signature, extract_method and move_member. The engine computes the
edits (``groovyscope/changeSignature``, ``extractMethod``, ``moveMember``) and writes nothing; each changed file comes
back as one exact replacement, so the dry run plans one Kiro ``write`` per file (the user sees Kiro's diff), gated on a
preview of the file as it would be, with the advice the gate gives. With APPLY the edits are written here after
asking, recompiled, and rolled back on new compile errors.
"""

from __future__ import annotations

import os
import time
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any

from groovyscope import gate, interaction, mcp_reuse, plans
from groovyscope.edits import FileEdit, apply_verified, changed_line_count, read_document
from groovyscope.mcp_edit import edit_settings, gate_summary, unified
from groovyscope.paths import Call, relative_path, to_uri, uri_to_path

Clock = Callable[[], float]
EXTRA_KEYS = ("callSites", "declaration", "method", "parameters", "returns", "static", "from", "to", "madePublic")


def file_changes(files: Sequence[Mapping[str, Any]]) -> list[FileEdit] | str:
    """The engine's ``files`` as edits of the documents on disk; the reason when a file no longer holds its
    ``oldStr`` exactly once (it changed since the engine read it)."""
    changed: list[FileEdit] = []
    for item in files:
        path = uri_to_path(str(item["uri"]))
        before = read_document(path) if path.is_file() else ""
        old, new = str(item["oldStr"]), str(item["newStr"])
        if before.count(old) != 1:
            return f"{path.name} changed since the engine read it; ask again"
        changed.append({"path": path, "before": before, "after": before.replace(old, new, 1)})
    return changed


def refactor(
    root: Path,
    call: Call,
    settings: Mapping[str, Any],
    confirm_policy: str,
    clock: Clock,
    request: str,
    params: dict[str, Any],
    *,
    action: str,
    apply: bool,
) -> dict[str, Any]:
    """The shared flow: ask the engine, gate each changed file, then plan the writes or apply them."""
    raw = call(request, {"root": str(root), **params})
    if raw.get("refused"):
        return {"applied": False, "refused": raw["refused"]}
    changed = file_changes(raw.get("files") or [])
    if isinstance(changed, str):
        return {"applied": False, "reason": changed}
    result: dict[str, Any] = {key: raw[key] for key in EXTRA_KEYS if key in raw}
    comments = gate.comment_policy({"comments": settings.get("comments") or {}})
    reuse = gate.reuse_settings(settings)
    limits = edit_settings(settings)
    files: list[dict[str, Any]] = []
    blocked = False
    for edit in changed:
        verdict = gate.check_candidate(
            call,
            root,
            edit["path"],
            before=edit["before"],
            after=edit["after"],
            mode="block",
            comments=comments,
            reuse=reuse,
        )
        blocked = blocked or verdict["decision"] == "block"
        shown = relative_path(edit["path"].as_uri(), root)
        files.append(
            {
                "path": shown,
                "gate": gate_summary(verdict),
                "diff": "\n".join(unified(edit["before"], edit["after"], shown)),
            }
        )
    result["files"] = files
    if not apply:
        store = plans.store_for(root, dict(os.environ))
        for entry, edit, item in zip(files, changed, raw.get("files") or [], strict=True):
            plan = plans.make(
                edit["path"],
                edit["before"],
                edit["after"],
                old_str=str(item["oldStr"]),
                new_str=str(item["newStr"]),
                now=clock(),
            )
            plans.save(store, plan, kept=limits["plansKept"], clock=clock, ttl_minutes=limits["planTtlMinutes"])
            entry["plan"] = {"plan_id": plan["plan_id"], "old_str": plan["old_str"], "new_str": plan["new_str"]}
        result["applyWith"] = (
            "do not apply: the gate blocks a file; change the request and plan again"
            if blocked
            else "one write per file: command str_replace with that path, old_str and new_str exactly as given"
        )
        return result
    if blocked:
        return {**result, "applied": False, "reason": "the gate blocks a file; nothing was written"}
    question = interaction.write_question(
        action,
        [(relative_path(e["path"].as_uri(), root), changed_line_count(e["before"], e["after"])) for e in changed],
    )
    decision = interaction.confirm(question, policy=confirm_policy)
    if decision in {interaction.DECLINED, interaction.CANCELLED}:
        return {**result, "applied": False, "confirmation": decision, "note": "the user said no; nothing was written"}
    result.update({"applied": True, **apply_verified(root, call, changed, flag="applied", what="the refactoring")})
    if decision != interaction.UNSUPPORTED:
        result["confirmation"] = decision
    return result


def refactor_tools(
    root: Path,
    call: Call,
    cap: int,
    settings: Mapping[str, Any],
    *,
    confirm_policy: str = interaction.POLICY_AUTO,
    clock: Clock = time.time,
) -> dict[str, Callable[..., Any]]:
    """change_signature, extract_method and move_member, bound to one workspace and engine session."""

    def run(request: str, params: dict[str, Any], action: str, apply: bool) -> dict[str, Any]:
        return refactor(root, call, settings, confirm_policy, clock, request, params, action=action, apply=apply)

    def change_signature(
        path: str,
        symbol: str,
        parameters: list[dict[str, Any]],
        return_type: str | None = None,
        line: int | None = None,
        apply: bool = False,
    ) -> dict[str, Any]:
        """Change SYMBOL's parameters (the full new list, in order: [{name, type, default?}]; a new parameter needs a
        default when it has callers) and RETURN_TYPE, with every static call site; refused when a call is matched by
        name only. Dry run: one gated write per file. APPLY writes them."""
        params: dict[str, Any] = {"uri": to_uri(root, path), "symbol": symbol, "parameters": parameters}
        if return_type:
            params["returnType"] = return_type
        if line is not None:
            params["line"] = line
        return run("groovyscope/changeSignature", params, f"Change the signature of {symbol}", apply)

    def extract_method(path: str, start_line: int, end_line: int, name: str, apply: bool = False) -> dict[str, Any]:
        """Extract lines START_LINE to END_LINE (whole statements of one method) of PATH into a method NAME, its
        parameters and return value from the data flow. Says first when existing code already does it. Dry run:
        the gated write. APPLY writes it."""
        uri = to_uri(root, path)
        selected = read_document(uri_to_path(uri)).splitlines()[start_line - 1 : end_line]
        reuse = mcp_reuse.reuse_tools(root, call, cap, settings)["suggest_reuse"](text="\n".join(selected), path=path)
        params = {"uri": uri, "startLine": start_line, "endLine": end_line, "name": name}
        result = run("groovyscope/extractMethod", params, f"Extract lines {start_line}-{end_line} into {name}", apply)
        if reuse.get("matches"):
            result["existing"] = reuse["matches"][:3]
            result["note"] = "existing code already does this: call it instead of extracting a new method"
        return result

    def move_member(
        path: str, symbol: str, to_class: str, line: int | None = None, apply: bool = False
    ) -> dict[str, Any]:
        """Move SYMBOL (a static method, or one using no instance state) from PATH to TO_CLASS, with its imports and
        every static call site; refused when a call is matched by name only. Dry run: the gated writes. APPLY writes
        them."""
        params: dict[str, Any] = {"uri": to_uri(root, path), "symbol": symbol, "toClass": to_class}
        if line is not None:
            params["line"] = line
        return run("groovyscope/moveMember", params, f"Move {symbol} to {to_class}", apply)

    return {"change_signature": change_signature, "extract_method": extract_method, "move_member": move_member}
