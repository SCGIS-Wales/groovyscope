"""The coverage threshold: line coverage per changed source file (``scope: changed``) or of the workspace
(``scope: overall``) against ``coverage.minimumPercent``, from the build's JaCoCo XML report.

Only a fresh report counts: one written after the last change to the changed files. A stale or missing report
never blocks; the answer says which command produces one. Pure apart from reading the report and file times.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any, Literal, TypedDict

from groovyscope import config as config_module
from groovyscope import reports

Verdict = Literal["pass", "below", "stale", "missing", "off"]
MissedRange = tuple[int, int]


class CoveragePolicy(TypedDict):
    minimumPercent: int
    scope: str  # changed | overall
    requireFreshReport: bool


class FileCoverage(TypedDict):
    path: str
    percent: int
    covered: int
    missed: int
    missedLines: list[int]


class Assessment(TypedDict, total=False):
    verdict: Verdict
    threshold: int
    scope: str
    overall: int | None
    changedClasses: list[dict[str, Any]]
    below: list[dict[str, Any]]
    report: str
    reportAgeSeconds: int
    note: str


def policy(effective: Mapping[str, Any]) -> CoveragePolicy:
    """``coverage`` with the defaults filled in."""
    section: Mapping[str, Any] = effective.get("coverage") or {}

    def value(key: str) -> Any:
        return section.get(key, config_module.default("coverage", key))

    return {
        "minimumPercent": int(value("minimumPercent")),
        "scope": str(value("scope")),
        "requireFreshReport": bool(value("requireFreshReport")),
    }


def percent(covered: int, missed: int) -> int:
    total = covered + missed
    return int(100 * covered / total) if total else 100


def by_file(document: Any, root: Path) -> dict[str, FileCoverage]:
    """Line coverage per source file of a parsed JaCoCo report, keyed by the workspace-relative path."""
    found: dict[str, FileCoverage] = {}
    for package in document.findall("package"):
        package_name = package.get("name", "")
        for source in package.findall("sourcefile"):
            path = reports.source_path(root, package_name, source.get("name", ""))
            missed, covered = reports.counter(source, "LINE")
            found[path] = {
                "path": path,
                "percent": percent(covered, missed),
                "covered": covered,
                "missed": missed,
                "missedLines": [  # missed instructions and none covered: the lines JaCoCo shows red
                    int(line.get("nr", 0))
                    for line in source.findall("line")
                    if int(line.get("mi", 0)) > 0 and int(line.get("ci", 0)) == 0
                ],
            }
    return found


def ranges(lines: Sequence[int]) -> str:
    """``3-5, 9`` from ``[3, 4, 5, 9]``."""
    parts: list[str] = []
    for n in sorted(lines):
        if parts and n == int(parts[-1].split("-")[-1]) + 1:
            parts[-1] = f"{parts[-1].split('-')[0]}-{n}"
        else:
            parts.append(str(n))
    return ", ".join(parts)


def is_main_source(path: str) -> bool:
    """A changed file whose class JaCoCo measures: Groovy or Java under a main source set, not a test."""
    return path.endswith((".groovy", ".java")) and "/test/" not in f"/{path}" and "/it/" not in f"/{path}"


def report_age(report: Path, changed: Sequence[str], root: Path, stat: Callable[[Path], float]) -> float | None:
    """Seconds between the newest changed file and the report (positive: the report is newer); None when no
    changed file exists to compare with."""
    times = []
    for path in changed:
        try:
            times.append(stat(root / path))
        except OSError:
            continue
    if not times:
        return None
    return stat(report) - max(times)


def assess(
    root: Path,
    rules: CoveragePolicy,
    changed: Sequence[str],
    *,
    configured_report: str | None,
    command: str,
    stat: Callable[[Path], float] = lambda path: path.stat().st_mtime,
) -> Assessment:
    """The coverage verdict for ``changed`` (workspace-relative paths): ``below`` (a fresh report under the
    threshold: blocking), ``pass``, ``stale`` or ``missing`` (never blocking: the note says how to refresh it), or
    ``off`` when ``coverage.minimumPercent`` is 0."""
    if rules["minimumPercent"] <= 0:
        return {"verdict": "off"}
    base: Assessment = {"threshold": rules["minimumPercent"], "scope": rules["scope"]}
    report = reports.jacoco_file(root, configured_report)
    if report is None:
        return {**base, "verdict": "missing", "note": f"no JaCoCo report; produce one with `{command}`"}
    try:
        document = reports.parse_report(report)
    except ValueError as exc:
        return {**base, "verdict": "missing", "note": str(exc)}
    main = [path for path in changed if is_main_source(path)]
    age = report_age(report, main, root, stat)
    missed, covered = reports.counter(document, "LINE")
    files = by_file(document, root)
    rows: list[dict[str, Any]] = [
        {
            "class": path,
            "percent": files[path]["percent"],
            "missedLines": ranges(files[path]["missedLines"]),
        }
        for path in main
        if path in files
    ]
    result: Assessment = {
        **base,
        "overall": percent(covered, missed),
        "changedClasses": rows,
        "report": report.relative_to(root).as_posix() if report.is_relative_to(root) else str(report),
    }
    if age is not None:
        result["reportAgeSeconds"] = int(age)
    if rules["requireFreshReport"] and age is not None and age < 0:
        return {**result, "verdict": "stale", "note": f"the report predates the change; refresh it with `{command}`"}
    overall = percent(covered, missed)
    below: list[dict[str, Any]]
    if rules["scope"] == "overall":
        under = overall < rules["minimumPercent"]
        below = [{"class": "(workspace)", "percent": overall, "missedLines": ""}] if under else []
    else:
        below = [row for row in rows if int(row["percent"]) < rules["minimumPercent"]]
    return {**result, "below": below, "verdict": "below" if below else "pass"}


def with_verdict(verdict: str, found: Assessment) -> str:
    """A review verdict made blocking by changed classes under the threshold."""
    under = f"{len(found.get('below') or [])} changed class(es) under {found.get('threshold')}% line coverage"
    if verdict.startswith("request changes"):
        return f"{verdict}, and {under}"
    return f"request changes: {under}"


def lines(found: Assessment) -> list[str]:
    """The assessment as the lines ``verify`` and the stop gate print."""
    verdict = found.get("verdict")
    if verdict in {None, "off"}:
        return []
    if verdict in {"missing", "stale"}:
        return [f"coverage: {found.get('note')}"]
    head = f"coverage: {found.get('scope')} classes at {found.get('threshold')}% or above"
    if verdict == "pass":
        return [f"{head}: yes (workspace {found.get('overall')}%)"]
    out = [f"{head}: no, {len(found.get('below') or [])} under (workspace {found.get('overall')}%)"]
    for row in found.get("below") or []:
        missed = f", missed lines {row['missedLines']}" if row.get("missedLines") else ""
        out.append(f"  {row['class']}: {row['percent']}%{missed}")
    return out
