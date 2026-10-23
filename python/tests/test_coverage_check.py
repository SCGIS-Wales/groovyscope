"""The coverage threshold: per changed file or overall, only a fresh JaCoCo report blocks."""

from __future__ import annotations

import os
from pathlib import Path

from groovyscope import coverage_check

REPORT = """<?xml version="1.0" encoding="UTF-8"?>
<report name="demo">
  <package name="demo">
    <sourcefile name="Good.groovy">
      <line nr="3" mi="0" ci="4"/>
      <counter type="LINE" missed="1" covered="9"/>
    </sourcefile>
    <sourcefile name="Weak.groovy">
      <line nr="4" mi="2" ci="0"/>
      <line nr="5" mi="1" ci="0"/>
      <line nr="6" mi="1" ci="1"/>
      <line nr="9" mi="3" ci="0"/>
      <counter type="LINE" missed="6" covered="4"/>
    </sourcefile>
  </package>
  <counter type="LINE" missed="7" covered="13"/>
</report>
"""
RULES: coverage_check.CoveragePolicy = {"minimumPercent": 80, "scope": "changed", "requireFreshReport": True}
CHANGED = [
    "src/main/groovy/demo/Good.groovy",
    "src/main/groovy/demo/Weak.groovy",
    "src/test/groovy/demo/WeakSpec.groovy",
]


def workspace(tmp_path: Path, *, report_newer: bool = True) -> Path:
    source = tmp_path / "src" / "main" / "groovy" / "demo"
    source.mkdir(parents=True)
    for name in ("Good", "Weak"):
        (source / f"{name}.groovy").write_text(f"class {name} {{}}\n", encoding="utf-8")
    report = tmp_path / "build" / "reports" / "jacoco" / "test" / "jacocoTestReport.xml"
    report.parent.mkdir(parents=True)
    report.write_text(REPORT, encoding="utf-8")
    stamp = (source / "Weak.groovy").stat().st_mtime
    os.utime(report, (stamp + (10 if report_newer else -10),) * 2)
    return tmp_path


def assess(root: Path, rules: coverage_check.CoveragePolicy = RULES) -> coverage_check.Assessment:
    return coverage_check.assess(root, rules, CHANGED, configured_report=None, command="groovyscope verify --coverage")


def test_a_fresh_report_with_a_changed_class_under_the_threshold_blocks(tmp_path: Path) -> None:
    found = assess(workspace(tmp_path))
    assert found["verdict"] == "below" and found["overall"] == 65 and found["reportAgeSeconds"] == 10
    assert found["changedClasses"] == [
        {"class": "src/main/groovy/demo/Good.groovy", "percent": 90, "missedLines": ""},
        {"class": "src/main/groovy/demo/Weak.groovy", "percent": 40, "missedLines": "4-5, 9"},
    ]
    assert found["below"] == [found["changedClasses"][1]]
    assert coverage_check.lines(found) == [
        "coverage: changed classes at 80% or above: no, 1 under (workspace 65%)",
        "  src/main/groovy/demo/Weak.groovy: 40%, missed lines 4-5, 9",
    ]
    assert coverage_check.with_verdict("approve: nothing blocking", found) == (
        "request changes: 1 changed class(es) under 80% line coverage"
    )
    assert coverage_check.with_verdict("request changes: 2 blocking", found).endswith(
        ", and 1 changed class(es) under 80% line coverage"
    )


def test_overall_scope_and_passing(tmp_path: Path) -> None:
    root = workspace(tmp_path)
    overall = assess(root, {**RULES, "scope": "overall"})
    assert overall["verdict"] == "below" and overall["below"][0]["class"] == "(workspace)"
    relaxed = assess(root, {**RULES, "minimumPercent": 30})
    assert relaxed["verdict"] == "pass" and coverage_check.lines(relaxed)[0].endswith("yes (workspace 65%)")
    assert assess(root, {**RULES, "minimumPercent": 0}) == {"verdict": "off"}
    assert coverage_check.lines({"verdict": "off"}) == []


def test_a_stale_or_missing_report_never_blocks(tmp_path: Path) -> None:
    stale = assess(workspace(tmp_path, report_newer=False))
    assert stale["verdict"] == "stale" and "groovyscope verify --coverage" in stale["note"]
    assert coverage_check.lines(stale) == [f"coverage: {stale['note']}"]
    lenient = assess(tmp_path, {**RULES, "requireFreshReport": False})
    assert lenient["verdict"] == "below"
    empty = tmp_path / "empty"
    empty.mkdir()
    assert assess(empty)["verdict"] == "missing"
    report = tmp_path / "build" / "reports" / "jacoco" / "test" / "jacocoTestReport.xml"
    report.write_text("<nope/>", encoding="utf-8")
    assert "not a JaCoCo report" in assess(tmp_path)["note"]


def test_helpers() -> None:
    assert coverage_check.ranges([9, 3, 4, 5, 7]) == "3-5, 7, 9" and coverage_check.ranges([]) == ""
    assert coverage_check.percent(0, 0) == 100 and coverage_check.percent(1, 3) == 25
    assert coverage_check.is_main_source("src/main/groovy/A.groovy")
    assert not coverage_check.is_main_source("src/test/groovy/ASpec.groovy") and not coverage_check.is_main_source(
        "a.md"
    )
    assert coverage_check.policy({"coverage": {"scope": "overall"}}) == {
        "minimumPercent": 80,
        "scope": "overall",
        "requireFreshReport": True,
    }
    assert coverage_check.report_age(Path("r"), ["gone.groovy"], Path("/nowhere"), lambda p: p.stat().st_mtime) is None
