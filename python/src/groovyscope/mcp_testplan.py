"""test_plan: for a method, or every changed method of the branch, the tests that already reach it (unit and
integration, with the Gradle command for each), what a fresh JaCoCo report says no test runs (lines and branches),
the reaching tests a check marks as repeating another (GS044, GS047), and ``needed``: one line per gap, nothing
else. The agent adds only what ``needed`` lists, and extends a data table before adding a feature method.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any

from groovyscope import changes, coverage_check, reports, verify

REDUNDANT_CODES = ("GS044", "GS047")
INTEGRATION_SUFFIXES = ("IT", "ITCase", "IntegrationTest")
STRING_TYPES = frozenset({"String", "CharSequence", "GString"})
COLLECTION_TYPES = ("List", "Set", "Map", "Collection", "Iterable", "[]")
NUMBER_TYPES = frozenset({"int", "long", "short", "Integer", "Long", "Short", "BigInteger", "BigDecimal", "double"})
BOOLEAN_TYPES = frozenset({"boolean", "Boolean"})
PRIMITIVES = frozenset({"int", "long", "short", "byte", "char", "float", "double", "boolean"})


def boundaries(parameters: Sequence[str]) -> list[str]:
    """The boundary inputs of a method's parameters (``String name``): null and empty text, empty collections,
    zero and negative numbers, both booleans; nothing for a type it cannot tell (``def``)."""
    found: list[str] = []
    for parameter in parameters:
        kind, _, name = parameter.strip().rpartition(" ")
        base = kind.split("<", 1)[0].strip()
        if base in STRING_TYPES:
            found.append(f"{name}: null and empty")
        elif any(base.endswith(c) for c in COLLECTION_TYPES):
            found.append(f"{name}: empty" + ("" if base in PRIMITIVES else " and null"))
        elif base in NUMBER_TYPES:
            found.append(f"{name}: 0 and a negative value")
        elif base in BOOLEAN_TYPES:
            found.append(f"{name}: true and false")
    return found


def line_counters(document: Any, root: Path, path: str) -> dict[int, tuple[int, int, int, int]]:
    """``{line: (missed instructions, covered instructions, missed branches, covered branches)}`` of one source
    file in a parsed JaCoCo report."""
    for package in document.findall("package"):
        for source in package.findall("sourcefile"):
            if reports.source_path(root, package.get("name", ""), source.get("name", "")) != path:
                continue
            return {
                int(line.get("nr", 0)): (
                    int(line.get("mi", 0)),
                    int(line.get("ci", 0)),
                    int(line.get("mb", 0)),
                    int(line.get("cb", 0)),
                )
                for line in source.findall("line")
            }
    return {}


def gaps(counters: Mapping[int, tuple[int, int, int, int]], start: int, end: int) -> list[str]:
    """The lines of ``start``..``end`` no test runs, as runs, and the branches never taken, one line each."""
    found: list[str] = []
    missed = [n for n in range(start, end + 1) if n in counters and counters[n][0] > 0 and counters[n][1] == 0]
    if missed:
        found += [f"lines {span}: no test runs them" for span in coverage_check.ranges(missed).split(", ")]
    for n in range(start, end + 1):
        _, covered, missed_branches, covered_branches = counters.get(n, (0, 0, 0, 0))
        if covered and missed_branches:
            total = missed_branches + covered_branches
            found.append(f"line {n}: {missed_branches} of {total} branches never taken")
    return found


def split_tests(tests: Sequence[Mapping[str, Any]]) -> dict[str, list[str]]:
    """The reaching tests as ``Class.method`` names, unit apart from integration (a source set other than
    ``test``, or an ``IT``, ``ITCase`` or ``IntegrationTest`` class)."""
    split: dict[str, list[str]] = {"unit": [], "integration": []}
    for test in tests:
        name = str(test.get("className")) + (f".{test['method']}" if test.get("method") else "")
        integration = test.get("sourceSet") != "test" or str(test.get("className")).endswith(INTEGRATION_SUFFIXES)
        split["integration" if integration else "unit"].append(name)
    return split


def method_plan(
    root: Path,
    method: Mapping[str, Any],
    impact: Mapping[str, Any],
    *,
    coverage: tuple[Any, bool] | None,
    redundant: Sequence[Mapping[str, Any]],
    settings: Mapping[str, Any],
    cap: int,
) -> dict[str, Any]:
    """One method's plan. ``coverage`` is the parsed report and whether it is newer than the method's file."""
    path = str(method.get("path") or "")
    start, end = (int(n) for n in (method.get("lines") or [0, 0]))
    reports_found = impact.get("reports") or []
    tests = verify.affected_tests(reports_found[:1], [], verify.is_test_path)
    planned = verify.plan(root, reports_found[:1], [], settings, limit=cap)
    entry: dict[str, Any] = {
        "symbol": f"{method.get('class')}.{method.get('name')}",
        "path": path,
        "lines": [start, end],
        "branches": method.get("branches"),
        "tests": split_tests(tests),
    }
    for key in ("command", "inCi"):
        if planned.get(key):
            entry[key] = planned[key]
    needed: list[str]
    if coverage is not None and coverage[1]:
        needed = gaps(line_counters(coverage[0], root, path), start, end)
        entry["coverage"] = "fresh report: " + ("gaps listed in needed" if needed else "every line and branch runs")
    else:
        needed = [f"boundary {item}" for item in boundaries(method.get("parameters") or [])]
        if not tests:
            needed.insert(0, "a test that calls it: no test reaches it")
        entry["coverage"] = "no fresh JaCoCo report: run `groovyscope verify --coverage` for the uncovered lines"
    reaching = {name.rsplit(".", 1)[0] for names in entry["tests"].values() for name in names}
    entry["redundant"] = [
        f"{r.get('path')}:{r.get('line')} [{r.get('code')}] {r.get('message')}"
        for r in redundant
        if any(str(r.get("path", "")).endswith(c.rsplit(".", 1)[-1] + ".groovy") for c in reaching)
    ][:cap]
    entry["needed"] = needed[:cap]
    return entry


def test_plan_tools(
    root: Path,
    cap: int,
    others: Mapping[str, Callable[..., Any]],
    *,
    report_settings: Mapping[str, Any],
    verify_settings: Mapping[str, Any],
) -> dict[str, Callable[..., Any]]:
    """test_plan, bound to one workspace; ``others`` supplies ``explain_code``, ``impact_of`` and ``check``."""

    def coverage_for(path: str) -> tuple[Any, bool] | None:
        report = reports.jacoco_file(root, report_settings.get("jacoco"))
        if report is None:
            return None
        try:
            document = reports.parse_report(report)
        except ValueError:
            return None
        age = coverage_check.report_age(report, [path], root, lambda p: p.stat().st_mtime)
        return (document, age is None or age >= 0)

    def redundant_findings(paths: Sequence[str]) -> list[dict[str, Any]]:
        if not paths:
            return []
        found = others["check"](paths=list(paths), include=["practices"], codes=list(REDUNDANT_CODES))
        return list(found.get("items") or [])

    def methods_of(symbol: str | None, base: str | None, staged: bool) -> list[dict[str, Any]] | str:
        if symbol:
            items = others["explain_code"](symbol=symbol).get("items") or []
            found = [item for item in items if item.get("kind") == "method"]
            return found or f"no method {symbol}; give Class.method"
        try:
            change = changes.changed_files(root, base, staged=staged)
        except (OSError, ValueError) as exc:
            return str(exc)
        methods: list[dict[str, Any]] = []
        for file in change["files"]:
            path = str(file["path"])
            if file["status"] == "deleted" or verify.is_test_path(path) or not coverage_check.is_main_source(path):
                continue
            items = others["explain_code"](path=path).get("items") or []
            for klass in items:
                for method in klass.get("methods") or []:
                    first, last = (int(n) for n in method.get("lines") or [0, 0])
                    if any(not (last < r0 or first > r1) for r0, r1 in file.get("ranges") or []):
                        methods.append({**method, "class": klass.get("name"), "path": path})
        return methods[:cap]

    def test_plan(symbol: str | None = None, base: str | None = None, staged: bool = False) -> dict[str, Any]:
        """Which tests to add, and only those: for SYMBOL (Class.method), or every method the branch changed
        against BASE (STAGED: the index), the tests that already reach it (unit and integration, with the Gradle
        command), the reaching tests that repeat another (GS044, GS047), and `needed`: from a fresh JaCoCo report
        the lines and branches no test runs, otherwise the untested method and its parameters' boundaries."""
        methods = methods_of(symbol, base, staged)
        if isinstance(methods, str):
            return {"error": methods}
        plans = []
        for method in methods:
            impact = others["impact_of"](f"{method.get('class')}#{method.get('name')}", "behaviour")
            tests = verify.affected_tests((impact.get("reports") or [])[:1], [], verify.is_test_path)
            redundant = redundant_findings(sorted({str(t["path"]) for t in tests}))
            coverage = coverage_for(str(method.get("path") or ""))
            plans.append(
                method_plan(
                    root, method, impact, coverage=coverage, redundant=redundant, settings=verify_settings, cap=cap
                )
            )
        needed = sum(len(p["needed"]) for p in plans)
        return {"methods": plans, "needed": needed}

    return {"test_plan": test_plan}
