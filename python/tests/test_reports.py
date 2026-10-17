"""SARIF logs and JaCoCo reports read as findings and coverage gaps."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from groovyscope import reports

SARIF = {
    "version": "2.1.0",
    "runs": [
        {
            "tool": {
                "driver": {
                    "name": "SpotBugs",
                    "rules": [
                        {
                            "id": "NP_NULL_ON_SOME_PATH",
                            "shortDescription": {"text": "Possible null pointer dereference"},
                            "defaultConfiguration": {"level": "error"},
                        }
                    ],
                }
            },
            "results": [
                {
                    "ruleId": "NP_NULL_ON_SOME_PATH",
                    "message": {"text": "value may be null"},
                    "locations": [
                        {
                            "physicalLocation": {
                                "artifactLocation": {"uri": "com/acme/Thing.groovy", "uriBaseId": "%SRCROOT%"},
                                "region": {"startLine": 12, "startColumn": 5},
                            }
                        }
                    ],
                },
                {
                    "ruleId": "DM_DEFAULT_ENCODING",
                    "level": "note",
                    "locations": [
                        {
                            "physicalLocation": {
                                "artifactLocation": {"uri": "src/main/groovy/com/acme/Thing.groovy"},
                                "region": {"startLine": 3},
                            }
                        }
                    ],
                },
                {"ruleId": "", "level": "none", "message": {"text": "unplaced"}},
                "not a result",
            ],
        }
    ],
}

JACOCO = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
<report name="demo">
  <package name="com/acme">
    <class name="com/acme/Thing" sourcefilename="Thing.groovy">
      <method name="run" desc="()V" line="5"><counter type="LINE" missed="4" covered="0"/></method>
      <method name="helper" desc="()V" line="12"><counter type="LINE" missed="1" covered="3"/></method>
      <method name="fine" desc="()V" line="20"><counter type="LINE" missed="0" covered="2"/></method>
      <counter type="LINE" missed="5" covered="5"/>
    </class>
    <class name="com/acme/Thing$Inner" sourcefilename="Thing.groovy">
      <counter type="LINE" missed="0" covered="1"/>
    </class>
  </package>
  <counter type="LINE" missed="5" covered="6"/>
</report>
"""


def test_sarif_findings_are_located_and_levelled(tmp_path: Path) -> None:
    source = tmp_path / "src/main/groovy/com/acme/Thing.groovy"
    source.parent.mkdir(parents=True)
    source.write_text("class Thing {}\n", encoding="utf-8")
    log = tmp_path / "build/reports/spotbugs/spotbugs.sarif.json"
    log.parent.mkdir(parents=True)
    log.write_text(json.dumps(SARIF), encoding="utf-8")
    assert reports.sarif_files(tmp_path, []) == [log]
    assert reports.sarif_files(tmp_path, ["missing.sarif"]) == []
    found = reports.read_sarif(log, tmp_path)
    assert found[0] == {
        "path": "src/main/groovy/com/acme/Thing.groovy",
        "line": 12,
        "col": 5,
        "severity": "error",
        "source": "spotbugs",
        "code": "NP_NULL_ON_SOME_PATH",
        "message": "value may be null",
    }
    assert found[1]["severity"] == "info" and found[1]["path"] == "src/main/groovy/com/acme/Thing.groovy"
    assert found[2]["path"] == "" and found[2]["code"] == "spotbugs" and found[2]["message"] == "unplaced"
    merged, notes = reports.merge_sarif(tmp_path, [], explicit=[tmp_path / "nope.sarif"])
    assert len(merged) == 3 and notes and "nope.sarif" in notes[0]


def test_sarif_rejects_non_logs(tmp_path: Path) -> None:
    bad = tmp_path / "x.sarif"
    bad.write_text("{not json", encoding="utf-8")
    with pytest.raises(ValueError, match="not valid JSON"):
        reports.read_sarif(bad, tmp_path)
    bad.write_text('{"runs": "nope"}', encoding="utf-8")
    with pytest.raises(ValueError, match="no runs"):
        reports.read_sarif(bad, tmp_path)
    _, notes = reports.merge_sarif(tmp_path, ["x.sarif"])
    assert "no runs" in notes[0]


def test_locate_falls_back_to_a_unique_suffix_match(tmp_path: Path) -> None:
    (tmp_path / "src/main/groovy/a/b").mkdir(parents=True)
    (tmp_path / "src/main/groovy/a/b/C.groovy").write_text("", encoding="utf-8")
    assert reports.locate(tmp_path, "b/C.groovy") == "src/main/groovy/a/b/C.groovy"
    assert reports.locate(tmp_path, "file:///elsewhere/C.groovy") == "elsewhere/C.groovy"
    (tmp_path / "src/test/groovy/x/b").mkdir(parents=True)
    (tmp_path / "src/test/groovy/x/b/C.groovy").write_text("", encoding="utf-8")
    assert reports.locate(tmp_path, "b/C.groovy") == "b/C.groovy"  # ambiguous: left as reported


def test_jacoco_gaps_and_summary(tmp_path: Path) -> None:
    source = tmp_path / "src/main/groovy/com/acme/Thing.groovy"
    source.parent.mkdir(parents=True)
    source.write_text("", encoding="utf-8")
    report = tmp_path / "build/reports/jacoco/test/jacocoTestReport.xml"
    report.parent.mkdir(parents=True)
    report.write_text(JACOCO, encoding="utf-8")
    assert reports.jacoco_file(tmp_path, None) == report
    assert reports.jacoco_file(tmp_path, "other.xml") is None
    gaps = reports.read_jacoco(report, tmp_path)
    assert [(g["name"], g["kind"], g["missed"], g["status"]) for g in gaps] == [
        ("Thing", "class", 5, "partial"),
        ("Thing.run", "method", 4, "uncovered"),
        ("Thing.helper", "method", 1, "partial"),
    ]
    assert gaps[0]["path"] == "src/main/groovy/com/acme/Thing.groovy" and gaps[0]["line"] == 5
    assert reports.coverage_summary(report) == {"lines": 11, "covered": 6, "missed": 5, "percent": 55}
    assert reports.filter_gaps(gaps, ["src/main/groovy/com"]) == gaps
    assert reports.filter_gaps(gaps, ["src/test"]) == []
    bad = tmp_path / "bad.xml"
    bad.write_text("<other/>", encoding="utf-8")
    with pytest.raises(ValueError, match="not a JaCoCo report"):
        reports.read_jacoco(bad, tmp_path)
    bad.write_text("<report", encoding="utf-8")
    with pytest.raises(ValueError, match="not a readable JaCoCo report"):
        reports.read_jacoco(bad, tmp_path)


def test_reports_settings_defaults() -> None:
    assert reports.reports_settings({}) == {"sarif": [], "jacoco": None}
    assert reports.reports_settings({"reports": {"sarif": ["a.sarif"], "jacoco": "j.xml"}}) == {
        "sarif": ["a.sarif"],
        "jacoco": "j.xml",
    }
