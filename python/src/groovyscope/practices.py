"""The practice catalogue on the Python side: grouping for ``review``, SARIF and short output for CI."""

from __future__ import annotations

import json
from collections.abc import Iterable, Mapping, Sequence
from functools import cache
from importlib import resources
from typing import Any

from groovyscope import __version__

CATALOG_PATH = ("_templates", "config", "practices", "practices.json")
DEFAULT_GROUP = "style"
UNUSED_GROUP = "unused"  # dead code: always the first group of a review, and listed in full
SARIF_SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json"
SARIF_LEVELS = {"error": "error", "warning": "warning", "info": "note", "hint": "note"}


@cache
def catalog() -> dict[str, Any]:
    """The bundled catalogue (groups and entries), parsed once."""
    text = resources.files("groovyscope").joinpath(*CATALOG_PATH).read_text(encoding="utf-8")
    loaded: dict[str, Any] = json.loads(text)
    return loaded


def entry(code: str) -> dict[str, Any] | None:
    for item in catalog()["entries"]:
        if item["code"] == code:
            return dict(item)
    return None


def link(code: str) -> str | None:
    found = entry(code)
    return f"{catalog()['docs']}#{found['section']}" if found else None


def group_of(code: str) -> str:
    """The checklist group a finding belongs to; uncatalogued CodeNarc rules count as style."""
    found = entry(code)
    return str(found["group"]) if found else DEFAULT_GROUP


LEVELS = {"error": 1, "warning": 2, "info": 3, "hint": 4}


def level(row: Mapping[str, Any]) -> int:
    """LSP severity number of a finding row (1 is worst), from ``level`` or the severity name."""
    return int(row["level"]) if "level" in row else LEVELS.get(str(row.get("severity")), 3)


def review(
    rows: Sequence[Mapping[str, Any]], top: int, *, full: Mapping[str, int] | None = None
) -> list[dict[str, Any]]:
    """Findings grouped by the section 15 checklist, worst groups first, each with counts and its top findings.
    The unused code group comes first whatever its severity, since it is the cheapest to fix and the easiest to
    lose among style findings; ``full`` gives groups listed beyond ``top`` (group id to how many)."""
    groups = {g["id"]: g for g in catalog()["groups"]}
    buckets: dict[str, list[Mapping[str, Any]]] = {}
    for row in rows:
        buckets.setdefault(group_of(str(row.get("code", ""))), []).append(row)
    result = []
    for group_id, items in buckets.items():
        info = groups.get(group_id, groups[DEFAULT_GROUP])
        ordered = sorted(items, key=lambda r: (level(r), str(r.get("path", "")), int(r.get("line", 0))))
        counts: dict[str, int] = {}
        for item in items:
            counts[str(item.get("severity", "info"))] = counts.get(str(item.get("severity", "info")), 0) + 1
        result.append(
            {
                "group": group_id,
                "title": info["title"],
                "check": info["check"],
                "counts": counts,
                "total": len(items),
                "worst": min(level(r) for r in items),
                "findings": ordered[: (full or {}).get(group_id, top)],
            }
        )
    result.sort(key=lambda g: (g["group"] != UNUSED_GROUP, g["worst"], -g["total"]))
    return result


def short_lines(rows: Iterable[Mapping[str, Any]]) -> list[str]:
    """``file:line:column: severity: message [code]``, the shape of ``groovyc --error-format short``."""
    return [f"{r['path']}:{r['line']}:{r['column']}: {r['severity']}: {r['message']} [{r['code']}]" for r in rows]


def sarif(rows: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    """A SARIF 2.1.0 log with one run, rule metadata from the catalogue where it has an entry."""
    rules: dict[str, dict[str, Any]] = {}
    results = []
    for row in rows:
        code = str(row.get("code") or row.get("source") or "groovyscope")
        if code not in rules:
            rule: dict[str, Any] = {"id": code}
            found = entry(code)
            if found:
                rule["shortDescription"] = {"text": found["title"]}
                rule["fullDescription"] = {"text": found["why"]}
                rule["help"] = {"text": found["fix"]}
                rule["helpUri"] = link(code)
                rule["properties"] = {"group": found["group"]}
            rules[code] = rule
        results.append(
            {
                "ruleId": code,
                "level": SARIF_LEVELS.get(str(row.get("severity")), "note"),
                "message": {"text": str(row.get("message", ""))},
                "locations": [
                    {
                        "physicalLocation": {
                            "artifactLocation": {"uri": str(row["path"])},
                            "region": {"startLine": int(row["line"]), "startColumn": int(row["column"])},
                        }
                    }
                ],
                "properties": {"source": row.get("source", "")},
            }
        )
    return {
        "$schema": SARIF_SCHEMA,
        "version": "2.1.0",
        "runs": [
            {
                "tool": {
                    "driver": {
                        "name": "groovyscope",
                        "version": __version__,
                        "informationUri": "https://github.com/SCGIS-Wales/groovyscope",
                        "rules": list(rules.values()),
                    }
                },
                "results": results,
            }
        ],
    }
