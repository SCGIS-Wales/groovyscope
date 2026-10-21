"""Unused code: the findings that say an import, variable, parameter, private member or statement does nothing.

They are cheap to fix and easy to lose: on a large file they sit among hundreds of style findings of the same
severity, so a page of ``check`` or the top five of a ``review`` group never shows them. Every answer that reports
findings therefore carries an ``unused`` block built from all findings before any page is cut, and an
``attention`` line (it sorts first in the compact JSON) whenever there is something to remove.
"""

from __future__ import annotations

import re
from collections.abc import Mapping, Sequence
from typing import Any, TypedDict

# CodeNarc rule -> the kind of thing that is unused. Imports the engine removes safely are FIXABLE.
KINDS: dict[str, str] = {
    "UnusedImport": "imports",
    "UnnecessaryGroovyImport": "imports",
    "DuplicateImport": "imports",
    "ImportFromSamePackage": "imports",
    "UnusedVariable": "variables",
    "UnusedMethodParameter": "parameters",
    "UnusedPrivateMethodParameter": "parameters",
    "UnusedPrivateMethod": "methods",
    "UnusedPrivateField": "fields",
    "UnusedArray": "statements",
    "UnusedObject": "statements",
    "DeadCode": "statements",
}
CODES: tuple[str, ...] = tuple(KINDS)
NAMED = re.compile(r"\[([^\]]+)\]")
FIXABLE = frozenset({"UnusedImport", "UnnecessaryGroovyImport", "DuplicateImport"})
ORDER = ("imports", "variables", "parameters", "methods", "fields", "statements")
HAND_EDIT = (
    "variables, parameters and private members need a hand edit: keep a right-hand side that has an effect, and "
    "remove a parameter's argument at every call (impact_of lists the callers)"
)


class Summary(TypedDict):
    total: int
    byKind: dict[str, int]
    items: list[dict[str, Any]]


def is_unused(code: object) -> bool:
    return str(code) in KINDS


def summary(findings: Sequence[Mapping[str, Any]], *, limit: int) -> Summary:
    """Every unused thing counted by kind, and the first ``limit`` of them (by path and line) as items. Two rules on
    one line about one kind of thing (an import both unused and provided by Groovy anyway) are one item."""
    found = sorted(
        (f for f in findings if is_unused(f.get("code"))), key=lambda f: (str(f.get("path")), int(f.get("line", 0)))
    )
    merged: dict[tuple[str, int, str], dict[str, Any]] = {}
    for finding in found:
        code = str(finding["code"])
        key = (str(finding.get("path")), int(finding.get("line", 0)), KINDS[code])
        entry = {"path": finding.get("path"), "line": finding.get("line"), "kind": KINDS[code], "code": code}
        entry.update(brief(str(finding.get("message") or ""), code))
        if code in FIXABLE:
            entry["fixable"] = True
        if key not in merged or ("name" in entry and "name" not in merged[key]):
            merged[key] = {**entry, **({"fixable": True} if merged.get(key, {}).get("fixable") else {})}
    items = list(merged.values())
    counts: dict[str, int] = {}
    for entry in items:
        counts[entry["kind"]] = counts.get(entry["kind"], 0) + 1
    return {"total": len(items), "byKind": {k: counts[k] for k in ORDER if k in counts}, "items": items[:limit]}


SHORT = {"UnnecessaryGroovyImport": "Groovy imports this by default", "ImportFromSamePackage": "same package"}


def brief(message: str, code: str = "") -> dict[str, str]:
    """The unused thing's ``name`` (CodeNarc puts it in brackets) and the sentence that names it, without the rule
    description CodeNarc puts first."""
    if code in SHORT:
        return {"message": SHORT[code]}
    sentences = [s.strip() for s in re.split(r"(?<=\.)\s+", message) if s.strip()]
    named = [s for s in sentences if NAMED.search(s)]
    found = NAMED.search(named[-1]) if named else None
    text = named[-1] if named else (sentences[0] if sentences else message)
    return {"name": found.group(1), "message": text} if found else {"message": text}


def counted(by_kind: Mapping[str, int]) -> str:
    """``1 import, 5 variables, 8 parameters``."""
    return ", ".join(f"{count} {kind if count != 1 else kind.rstrip('s')}" for kind, count in by_kind.items())


def attention(found: Summary, *, where: str = "") -> str | None:
    """The line an agent reads first when there is unused code, with how to remove it; None when there is none."""
    if not found["total"]:
        return None
    fixable = sum(1 for item in found["items"] if item.get("fixable"))
    line = f"unused code{where}: {counted(found['byKind'])}. Remove it before finishing"
    if fixable:
        line += f"; fix with codes {sorted(FIXABLE)} removes the imports safely"
    if any(kind != "imports" for kind in found["byKind"]):
        line += f"; {HAND_EDIT}"
    return line + ". The `unused` block lists each one."


def with_unused(result: dict[str, Any], findings: Sequence[Mapping[str, Any]], *, limit: int) -> dict[str, Any]:
    """``result`` with its ``unused`` block (always, zero included) and, when there is unused code, ``attention``."""
    found = summary(findings, limit=limit)
    result["unused"] = found
    line = attention(found)
    if line is not None:
        result["attention"] = line
    return result


def diverse(findings: Sequence[Mapping[str, Any]], severity_order: Mapping[str, int]) -> list[dict[str, Any]]:
    """Worst first, and within a severity one finding of each code before the second of any: a page then shows
    every kind of finding instead of the first hundred of the most frequent one."""
    seen: dict[str, int] = {}
    ranked = []
    for finding in sorted(findings, key=lambda f: (str(f.get("path")), int(f.get("line", 0)))):
        code = str(finding.get("code"))
        seen[code] = seen.get(code, 0) + 1
        ranked.append((severity_order.get(str(finding.get("severity")), 9), seen[code], finding))
    ranked.sort(key=lambda row: (row[0], row[1], str(row[2].get("path")), int(row[2].get("line", 0))))
    return [dict(row[2]) for row in ranked]
