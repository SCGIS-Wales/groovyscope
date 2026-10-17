"""Reports the build already produces, read into groovyscope's shapes: SARIF logs (SpotBugs and any other SARIF 2.1
tool) become findings next to the engine's own, and a JaCoCo XML report becomes the list of uncovered classes and
methods. Nothing is run; files are read as data.
"""

from __future__ import annotations

import json
import xml.etree.ElementTree as ET
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any, TypedDict

SARIF_SEVERITIES = {"error": "error", "warning": "warning", "note": "info", "none": "info"}
DEFAULT_SARIF = (
    "build/reports/spotbugs/spotbugs.sarif.json",
    "build/reports/spotbugs/main.sarif",
    "build/reports/spotbugs/spotbugs.sarif",
    "target/spotbugsSarif.json",
)
DEFAULT_JACOCO = "build/reports/jacoco/test/jacocoTestReport.xml"
SOURCE_ROOTS = ("src/main/groovy", "src/main/java", "src/test/groovy", "src/test/java", "src")
MAX_SARIF_BYTES = 50_000_000


class Gap(TypedDict):
    """A class or method with lines no test executed."""

    path: str
    name: str  # Class or Class.method
    kind: str  # class | method
    line: int
    missed: int
    covered: int
    status: str  # uncovered | partial


def sarif_files(root: Path, configured: Sequence[str]) -> list[Path]:
    """The SARIF files to merge: the configured ones (missing ones are skipped), else the build's usual SpotBugs
    output when present."""
    chosen = [root / name for name in (configured or DEFAULT_SARIF)]
    return [path for path in chosen if path.is_file()]


SourceIndex = dict[str, list[str]]


def source_index(root: Path) -> SourceIndex:
    """Every file under the source roots by base name (one walk, reused for every result of a SARIF log)."""
    index: SourceIndex = {}
    for source in SOURCE_ROOTS:
        folder = root / source
        if not folder.is_dir():
            continue
        for path in folder.rglob("*"):
            if path.is_file():
                index.setdefault(path.name, []).append(path.relative_to(root).as_posix())
    return index


def locate(root: Path, uri: str, index: SourceIndex | None = None) -> str:
    """A workspace-relative path for a SARIF artifact URI: as given when it exists, else the unique file under the
    source roots whose path ends with it (SpotBugs reports class-relative paths), else the URI unchanged."""
    cleaned = uri.removeprefix("file://").lstrip("/") if uri.startswith("file:") else uri
    cleaned = cleaned.replace("%SRCROOT%/", "").replace("%SRCROOT%", "")
    if (root / cleaned).is_file():
        return cleaned
    suffix = "/" + cleaned.lstrip("/")
    candidates = (source_index(root) if index is None else index).get(Path(cleaned).name, [])
    found = sorted({p for p in candidates if ("/" + p).endswith(suffix)})  # roots overlap: src and src/main/groovy
    return found[0] if len(found) == 1 else cleaned


def read_sarif(path: Path, root: Path) -> list[dict[str, Any]]:
    """Findings from one SARIF 2.1 log, in the shape ``check`` returns (path, line, col, severity, source, code,
    message). Raises ``ValueError`` for a file that is not a SARIF log."""
    if path.stat().st_size > MAX_SARIF_BYTES:
        msg = f"{path} is larger than {MAX_SARIF_BYTES // 1_000_000} MB; not read"
        raise ValueError(msg)
    try:
        log: Any = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        msg = f"{path} is not valid JSON: {exc}"
        raise ValueError(msg) from exc
    if not isinstance(log, dict) or not isinstance(log.get("runs"), list):
        msg = f"{path} is not a SARIF log (no runs)"
        raise ValueError(msg)
    findings: list[dict[str, Any]] = []
    index: SourceIndex | None = None
    for run in log["runs"]:
        if not isinstance(run, dict):
            continue
        driver = (run.get("tool") or {}).get("driver") or {}
        source = str(driver.get("name") or "sarif").lower().replace(" ", "")
        rules = {rule.get("id"): rule for rule in driver.get("rules") or [] if isinstance(rule, dict)}
        for result in run.get("results", []) or []:
            if not isinstance(result, dict):
                continue
            rule_id = str(result.get("ruleId") or "")
            rule = rules.get(rule_id, {})
            level = str(result.get("level") or (rule.get("defaultConfiguration") or {}).get("level") or "warning")
            location = (result.get("locations") or [{}])[0].get("physicalLocation") or {}
            uri = str((location.get("artifactLocation") or {}).get("uri") or "")
            region = location.get("region") or {}
            text = (result.get("message") or {}).get("text") or (rule.get("shortDescription") or {}).get("text")
            if uri and index is None:
                index = source_index(root)
            findings.append(
                {
                    "path": locate(root, uri, index) if uri else "",
                    "line": int(region.get("startLine") or 1),
                    "col": int(region.get("startColumn") or 1),
                    "severity": SARIF_SEVERITIES.get(level, "warning"),
                    "source": source,
                    "code": rule_id or source,
                    "message": str(text or rule_id),
                }
            )
    return findings


def merge_sarif(
    root: Path, configured: Sequence[str], *, explicit: Sequence[Path] = ()
) -> tuple[list[dict[str, Any]], list[str]]:
    """Findings from every SARIF file (configured or detected, plus ``explicit``), and the notes for files that
    could not be read. Never raises."""
    findings: list[dict[str, Any]] = []
    notes: list[str] = []
    for path in [*sarif_files(root, configured), *explicit]:
        try:
            findings.extend(read_sarif(path, root))
        except (OSError, ValueError) as exc:
            notes.append(str(exc))
        except (TypeError, AttributeError, KeyError) as exc:
            notes.append(f"{path} is not a SARIF log groovyscope can read ({exc.__class__.__name__}: {exc})")
    return (findings, notes)


def source_path(root: Path, package: str, source_file: str) -> str:
    """Where a JaCoCo package/sourcefile pair lives, by trying the usual roots; the bare relative path otherwise."""
    relative = f"{package}/{source_file}" if package else source_file
    for source in SOURCE_ROOTS:
        if (root / source / relative).is_file():
            return f"{source}/{relative}"
    return relative


def counter(node: ET.Element, kind: str) -> tuple[int, int]:
    for child in node.findall("counter"):
        if child.get("type") == kind:
            return (int(child.get("missed", 0)), int(child.get("covered", 0)))
    return (0, 0)


def parse_report(path: Path) -> ET.Element:
    try:
        document = ET.parse(path).getroot()  # noqa: S314 - a build report the user points at; no entity expansion
    except (OSError, ET.ParseError) as exc:
        msg = f"{path} is not a readable JaCoCo report: {exc}"
        raise ValueError(msg) from exc
    if document.tag != "report":
        msg = f"{path} is not a JaCoCo report (root element {document.tag!r})"
        raise ValueError(msg)
    return document


def read_jacoco(path: Path, root: Path) -> list[Gap]:
    """Classes and methods with uncovered lines from a JaCoCo XML report, most missed lines first. Raises
    ``ValueError`` when the file is not a JaCoCo report."""
    document = parse_report(path)
    gaps: list[Gap] = []
    for package in document.findall("package"):
        package_name = package.get("name", "")
        for klass in package.findall("class"):
            class_name = klass.get("name", "").rsplit("/", 1)[-1].replace("$", ".")
            source_file = klass.get("sourcefilename") or f"{class_name}.groovy"
            path_shown = source_path(root, package_name, source_file)
            missed, covered = counter(klass, "LINE")
            if missed == 0:
                continue
            first_line = min((int(m.get("line", 0)) for m in klass.findall("method") if m.get("line")), default=1)
            gaps.append(
                {
                    "path": path_shown,
                    "name": class_name,
                    "kind": "class",
                    "line": first_line,
                    "missed": missed,
                    "covered": covered,
                    "status": "uncovered" if covered == 0 else "partial",
                }
            )
            for method in klass.findall("method"):
                m_missed, m_covered = counter(method, "LINE")
                if m_missed == 0:
                    continue
                gaps.append(
                    {
                        "path": path_shown,
                        "name": f"{class_name}.{method.get('name', '')}",
                        "kind": "method",
                        "line": int(method.get("line", 0) or 0),
                        "missed": m_missed,
                        "covered": m_covered,
                        "status": "uncovered" if m_covered == 0 else "partial",
                    }
                )
    gaps.sort(key=lambda g: (-g["missed"], g["path"], g["line"]))
    return gaps


def coverage_summary(path: Path) -> dict[str, Any]:
    """Overall line coverage of a JaCoCo report (what a pipeline prints as ``Coverage=78%``)."""
    missed, covered = counter(parse_report(path), "LINE")
    total = missed + covered
    return {
        "lines": total,
        "covered": covered,
        "missed": missed,
        "percent": round(100 * covered / total) if total else 0,
    }


def jacoco_file(root: Path, configured: str | None) -> Path | None:
    candidate = root / (configured or DEFAULT_JACOCO)
    return candidate if candidate.is_file() else None


def filter_gaps(gaps: Sequence[Gap], paths: Sequence[str]) -> list[Gap]:
    """Gaps under any of ``paths`` (files or folders), or all when none are given."""
    if not paths:
        return list(gaps)
    wanted = [p.rstrip("/") for p in paths]
    return [g for g in gaps if any(g["path"] == w or g["path"].startswith(w + "/") for w in wanted)]


def reports_settings(config: Mapping[str, Any]) -> dict[str, Any]:
    section = config.get("reports")
    if not isinstance(section, Mapping):
        return {"sarif": [], "jacoco": None}
    return {"sarif": list(section.get("sarif") or []), "jacoco": section.get("jacoco")}
