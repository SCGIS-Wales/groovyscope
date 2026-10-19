"""JUnit XML test results, wherever they are: ``TEST-*.xml`` at any depth under a folder (Gradle
``build/test-results/<task>/``, Maven ``surefire-reports`` and ``failsafe-reports``, every module of a multi-module
build) or inside a ZIP archive (the ``artifacts.zip`` a CI pipeline keeps). Nothing is extracted to disk: archive
entries are read in memory, within size limits, and an XML file with a DOCTYPE is refused (JUnit XML has none, and
entity expansion is how a hostile file would attack the parser).

The summary names each failing test, its message, and the first stack frames in the project's own code and in
the test, so an agent never needs a script to read the reports.
"""

from __future__ import annotations

import fnmatch
import os
import re
import zipfile
from collections.abc import Callable, Iterator, Mapping, Sequence
from pathlib import Path
from typing import Any, TypedDict
from xml.etree import ElementTree as ET

REPORT_PATTERN = "TEST-*.xml"
PRUNED_DIRS = frozenset({".git", "node_modules", ".gradle", ".idea", ".venv", "__pycache__"})
MAX_REPORT_BYTES = 20_000_000  # one TEST-*.xml, read from disk or an archive
MAX_TOTAL_BYTES = 200_000_000  # all reports of one call together
MAX_REPORTS = 5000
MESSAGE_CHARS = 300
# Frames of these packages are the runtime, not the project: the first frame outside them is where it failed.
FRAMEWORK_PREFIXES = (
    "java.", "javax.", "jdk.", "sun.", "com.sun.", "kotlin.", "groovy.", "org.codehaus.groovy.", "org.apache.groovy.",
    "org.junit.", "junit.", "org.opentest4j.", "org.spockframework.", "spock.", "org.mockito.", "org.assertj.",
    "org.hamcrest.", "org.gradle.", "worker.org.gradle.", "org.apache.maven.", "net.bytebuddy.", "org.awaitility.",
)  # fmt: skip
# `at app.Store.save(Store.groovy:12)`, `at java.base/java.lang.Thread.run(Thread.java:1583)`, and a Spock feature
# `at app.StoreSpec.writes a row(StoreSpec.groovy:20)`: the module or class loader prefix is dropped.
FRAME = re.compile(r"^\s*at\s+(?:[\w.@-]*/)*(?P<method>[^()/]+?)\((?P<file>[^:()]+)(?::(?P<line>\d+))?\)\s*$")


class Failure(TypedDict):
    """One failing (or erroring) test."""

    className: str
    name: str
    kind: str  # failure | error
    message: str
    at: str  # first frame in the project's code: File.groovy:123 in Class.method
    test: str  # the frame inside the test class itself
    report: str  # which TEST-*.xml it came from (an archive entry is archive.zip!path)


class Summary(TypedDict):
    """Totals over every report found, and the failures."""

    reports: int
    tests: int
    failures: int
    errors: int
    skipped: int
    failed: list[Failure]
    notes: list[str]


def is_report(name: str) -> bool:
    return fnmatch.fnmatch(name.rsplit("/", 1)[-1], REPORT_PATTERN)


def walk_reports(root: Path) -> Iterator[Path]:
    """``TEST-*.xml`` files at any depth under ``root``, skipping VCS, dependency and IDE folders."""
    for folder, dirs, files in os.walk(root):
        dirs[:] = sorted(d for d in dirs if d not in PRUNED_DIRS)
        for name in sorted(files):
            if is_report(name):
                yield Path(folder) / name


def read_sources(source: Path) -> Iterator[tuple[str, bytes | None]]:
    """``(label, bytes)`` for every report in a folder or ZIP archive; bytes is None when a report is too large."""
    if source.is_file() and zipfile.is_zipfile(source):
        with zipfile.ZipFile(source) as archive:
            for info in archive.infolist():
                if info.is_dir() or not is_report(info.filename):
                    continue
                label = f"{source.name}!{info.filename}"
                if info.file_size > MAX_REPORT_BYTES or info.flag_bits & 0x1:  # too large, or encrypted
                    yield (label, None)
                    continue
                with archive.open(info) as handle:
                    yield (label, handle.read(MAX_REPORT_BYTES + 1))
        return
    if source.is_file():
        yield (str(source), read_limited(source) if is_report(source.name) else None)
        return
    for path in walk_reports(source):
        yield (str(path), read_limited(path))


def read_limited(path: Path) -> bytes | None:
    try:
        if path.stat().st_size > MAX_REPORT_BYTES:
            return None
        return path.read_bytes()
    except OSError:
        return None


def parse_report(data: bytes) -> ET.Element | None:
    """The XML root, or None when the file is not JUnit XML (or carries a DOCTYPE, which JUnit XML never does)."""
    if len(data) > MAX_REPORT_BYTES or b"<!DOCTYPE" in data[:4096].upper():
        return None
    try:
        return ET.fromstring(data)  # noqa: S314 - DOCTYPE (entity expansion) refused above
    except ET.ParseError:
        return None


def suites(root: ET.Element) -> list[ET.Element]:
    """``<testsuite>`` elements whether the root is one suite or a ``<testsuites>`` wrapper."""
    return [root] if root.tag == "testsuite" else list(root.iter("testsuite"))


def frames(trace: str) -> list[tuple[str, str, str]]:
    """``(Class.method, file, line)`` of each ``at`` line of a stack trace."""
    return [(m["method"], m["file"], m["line"] or "") for m in map(FRAME.match, trace.splitlines()) if m]


def frame_text(frame: tuple[str, str, str]) -> str:
    method, file, line = frame
    return f"{file}:{line} in {method}" if line else f"{file} in {method}"


def failure_of(case: ET.Element, label: str) -> Failure | None:
    problem = case.find("failure")
    kind = "failure"
    if problem is None:
        problem = case.find("error")
        kind = "error"
    if problem is None:
        return None
    class_name = case.get("classname") or ""
    trace = problem.text or ""
    message = (problem.get("message") or trace.strip().split("\n", 1)[0]).strip()
    stack = frames(trace)
    own = [f for f in stack if not f[0].startswith(FRAMEWORK_PREFIXES)]
    in_test = [f for f in own if f[0].startswith((f"{class_name}.", f"{class_name}$"))]
    return {
        "className": class_name,
        "name": case.get("name") or "",
        "kind": kind,
        "message": message[:MESSAGE_CHARS] + ("…" if len(message) > MESSAGE_CHARS else ""),
        "at": frame_text(own[0]) if own else "",
        "test": frame_text(in_test[0]) if in_test else "",
        "report": label,
    }


def summarise(sources: Sequence[Path], *, keep: Callable[[str], bool] = lambda _label: True) -> Summary:
    """Every report under ``sources`` (folders or ZIP archives) added up, failures listed in report order."""
    summary: Summary = {"reports": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0, "failed": [], "notes": []}
    total = 0
    for source in sources:
        if not source.exists():
            summary["notes"].append(f"{source}: not found")
            continue
        if source.is_file() and not is_report(source.name) and not zipfile.is_zipfile(source):
            summary["notes"].append(f"{source}: neither a ZIP archive nor a {REPORT_PATTERN} report")
            continue
        try:
            entries = list(read_sources(source))
        except (OSError, zipfile.BadZipFile) as exc:
            summary["notes"].append(f"{source}: cannot be read ({exc})")
            continue
        for label, data in entries:
            if not keep(label):
                continue
            if summary["reports"] >= MAX_REPORTS or total > MAX_TOTAL_BYTES:
                summary["notes"].append(f"stopped after {summary['reports']} report(s): the size limit was reached")
                return summary
            root = parse_report(data) if data is not None else None
            if root is None:
                summary["notes"].append(f"{label}: skipped (too large, encrypted, or not JUnit XML)")
                continue
            total += len(data or b"")
            summary["reports"] += 1
            add_report(summary, root, label)
    return summary


def add_report(summary: Summary, root: ET.Element, label: str) -> None:
    for suite in suites(root):
        for key in ("tests", "failures", "errors", "skipped"):
            summary[key] += int(suite.get(key) or 0)
        for case in suite.iter("testcase"):
            failure = failure_of(case, label)
            if failure is not None:
                summary["failed"].append(failure)


def default_sources(root: Path, given: Sequence[str] | None) -> list[Path]:
    """The folders and archives to read: those given (relative to ``root`` or absolute, ``~`` expanded), else the
    workspace root itself (every ``TEST-*.xml`` at any depth)."""
    if not given:
        return [root]
    return [(root / Path(item).expanduser()).resolve() for item in given]


def lines(summary: Mapping[str, Any], limit: int = 20) -> list[str]:
    """``412 test(s) in 3 report(s): 1 failed, 0 error(s), 2 skipped`` and one line per failure."""
    failed = summary.get("failed") or []
    head = (
        f"{summary['tests']} test(s) in {summary['reports']} report(s): {summary['failures']} failed, "
        f"{summary['errors']} error(s), {summary['skipped']} skipped"
    )
    out = [head]
    for item in failed[:limit]:
        where = f" at {item['at']}" if item["at"] else ""
        test = f" (test {item['test']})" if item["test"] and item["test"] != item["at"] else ""
        message = " ".join(item["message"].split())  # a Spock condition diagram spans lines
        out.append(f"  {item['kind']}: {item['className']}.{item['name']}: {message}{where}{test}")
    if len(failed) > limit:
        out.append(f"  +{len(failed) - limit} more")
    out += [f"  note: {note}" for note in summary.get("notes") or []]
    return out
