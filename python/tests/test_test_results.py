"""JUnit XML results read from folders at any depth and from ZIP archives, safely and within limits."""

from __future__ import annotations

import json
import zipfile
from pathlib import Path

import pytest
from click.testing import CliRunner

from groovyscope import cli, test_results

FAILING = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="app.StoreSpec" tests="3" skipped="1" failures="1" errors="0">
  <testcase name="writes a row" classname="app.StoreSpec" time="0.1">
    <failure message="Condition not satisfied:" type="org.spockframework.runtime.SpockComparisonFailure">x
\tat org.spockframework.runtime.SpockRuntime.verifyCondition(SpockRuntime.java:1)
\tat app.Store.save(Store.groovy:12)
\tat app.StoreSpec.writes a row(StoreSpec.groovy:20)
</failure>
  </testcase>
  <testcase name="reads" classname="app.StoreSpec" time="0.1"/>
  <testcase name="skips" classname="app.StoreSpec" time="0"><skipped/></testcase>
</testsuite>
"""
ERROR = """<testsuites><testsuite name="app.StoreIT" tests="1" failures="0" errors="1">
  <testcase name="shouldRead()" classname="app.StoreIT">
    <error type="java.lang.IllegalStateException">java.lang.IllegalStateException: no connection
\tat java.base/java.lang.Thread.run(Thread.java:1583)
</error>
  </testcase>
</testsuite></testsuites>
"""
PASSING = (
    '<testsuite name="app.OkTest" tests="2" failures="0" errors="0"><testcase name="a" classname="app.OkTest"/>'
    "</testsuite>"
)


def write(path: Path, text: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


def test_reports_are_found_at_any_depth_and_summarised(tmp_path: Path) -> None:
    write(tmp_path / "store/build/test-results/test/TEST-app.StoreSpec.xml", FAILING)
    write(tmp_path / "a/b/c/target/failsafe-reports/TEST-app.StoreIT.xml", ERROR)
    write(tmp_path / "build/test-results/test/TEST-app.OkTest.xml", PASSING)
    write(tmp_path / "build/test-results/test/results.xml", FAILING)  # not TEST-*.xml
    write(tmp_path / "node_modules/x/TEST-ignored.xml", FAILING)
    summary = test_results.summarise([tmp_path])
    assert (summary["reports"], summary["tests"], summary["failures"], summary["errors"], summary["skipped"]) == (
        3,
        6,
        1,
        1,
        1,
    )
    failure, error = sorted(summary["failed"], key=lambda f: f["kind"], reverse=True)
    assert failure == {
        "className": "app.StoreSpec",
        "name": "writes a row",
        "kind": "failure",
        "message": "Condition not satisfied:",
        "at": "Store.groovy:12 in app.Store.save",
        "test": "StoreSpec.groovy:20 in app.StoreSpec.writes a row",
        "report": str(tmp_path / "store/build/test-results/test/TEST-app.StoreSpec.xml"),
    }
    assert error["kind"] == "error" and error["message"] == "java.lang.IllegalStateException: no connection"
    assert error["at"] == "" and error["test"] == ""


def test_reports_inside_a_zip_archive_are_read_in_memory(tmp_path: Path) -> None:
    archive = tmp_path / "artifacts.zip"
    with zipfile.ZipFile(archive, "w") as zipped:
        zipped.writestr("module/build/test-results/test/TEST-app.StoreSpec.xml", FAILING)
        zipped.writestr("deep/er/TEST-app.StoreIT.xml", ERROR)
        zipped.writestr("build/reports/index.html", "<html/>")
        zipped.writestr("folder/", "")
    summary = test_results.summarise([archive])
    assert summary["reports"] == 2 and summary["failures"] == 1 and summary["errors"] == 1
    assert summary["failed"][0]["report"] == "artifacts.zip!module/build/test-results/test/TEST-app.StoreSpec.xml"
    assert sorted(p.name for p in tmp_path.iterdir()) == ["artifacts.zip"]  # nothing extracted


def test_hostile_or_broken_reports_are_skipped_with_a_note(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    bomb = '<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "aaaa">]><testsuite tests="1">&a;</testsuite>'
    write(tmp_path / "TEST-bomb.xml", bomb)
    write(tmp_path / "TEST-broken.xml", "<testsuite")
    write(tmp_path / "TEST-big.xml", PASSING)
    monkeypatch.setattr(test_results, "MAX_REPORT_BYTES", len(PASSING) - 1)
    summary = test_results.summarise([tmp_path, tmp_path / "missing", write(tmp_path / "bad.zip", "PK not a zip")])
    assert summary["reports"] == 0
    assert any("missing: not found" in note for note in summary["notes"])
    assert any("bad.zip: neither a ZIP archive" in note for note in summary["notes"])
    assert sum("skipped" in note for note in summary["notes"]) == 3


def test_oversized_and_encrypted_archive_entries_and_the_total_limit(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    archive = tmp_path / "artifacts.zip"
    with zipfile.ZipFile(archive, "w") as zipped:
        zipped.writestr("TEST-big.xml", FAILING)
        zipped.writestr("TEST-small.xml", PASSING)
    monkeypatch.setattr(test_results, "MAX_REPORT_BYTES", len(FAILING) - 1)
    summary = test_results.summarise([archive])
    assert summary["reports"] == 1 and summary["notes"] == ["artifacts.zip!TEST-big.xml: skipped (too large, "
                                                           "encrypted, or not JUnit XML)"]  # fmt: skip
    monkeypatch.setattr(test_results, "MAX_REPORTS", 1)
    write(tmp_path / "x/TEST-a.xml", PASSING)
    write(tmp_path / "x/TEST-b.xml", PASSING)
    limited = test_results.summarise([tmp_path / "x"])
    assert limited["reports"] == 1 and limited["notes"][-1].startswith("stopped after 1 report(s)")


def test_a_single_file_keep_and_default_sources(tmp_path: Path) -> None:
    report = write(tmp_path / "TEST-app.OkTest.xml", PASSING)
    other = write(tmp_path / "other.xml", PASSING)
    assert test_results.summarise([report])["reports"] == 1
    assert test_results.summarise([other])["notes"] == [f"{other}: neither a ZIP archive nor a TEST-*.xml report"]
    assert test_results.summarise([tmp_path], keep=lambda _label: False)["reports"] == 0
    assert test_results.default_sources(tmp_path, None) == [tmp_path]
    assert test_results.default_sources(tmp_path, ["a.zip"]) == [(tmp_path / "a.zip").resolve()]
    assert test_results.read_limited(tmp_path / "none") is None


def test_lines_name_each_failure_and_cap_the_list() -> None:
    failure = {"className": "app.S", "name": "t", "kind": "failure", "message": "bo\n  om"}
    failure["at"] = "S.groovy:3 in app.S.t"
    summary = {
        "reports": 1,
        "tests": 2,
        "failures": 2,
        "errors": 0,
        "skipped": 0,
        "failed": [{**failure, "test": "S.groovy:3 in app.S.t"}, {**failure, "at": "", "test": "x"}],
        "notes": ["n"],
    }
    assert test_results.lines(summary, 1) == [
        "2 test(s) in 1 report(s): 2 failed, 0 error(s), 0 skipped",
        "  failure: app.S.t: bo om at S.groovy:3 in app.S.t",
        "  +1 more",
        "  note: n",
    ]
    assert test_results.lines(summary)[2] == "  failure: app.S.t: bo om (test x)"


def test_the_cli_reads_folders_and_archives(tmp_path: Path) -> None:
    archive = tmp_path / "ci" / "artifacts.zip"
    archive.parent.mkdir()
    with zipfile.ZipFile(archive, "w") as zipped:
        zipped.writestr("TEST-app.StoreSpec.xml", FAILING)
    runner = CliRunner()
    shown = runner.invoke(cli.main, ["--root", str(tmp_path), "test-results", str(archive)])
    assert shown.exit_code == 1 and "failure: app.StoreSpec.writes a row" in shown.output
    payload = json.loads(runner.invoke(cli.main, ["--root", str(tmp_path), "test-results", "--json", "ci"]).output)
    assert payload["failures"] == 0 and "not found" in payload["notes"][0]  # relative to the current folder
    payload = json.loads(runner.invoke(cli.main, ["--root", str(tmp_path), "test-results", "--json"]).output)
    assert payload["reports"] == 0  # the workspace walk reads TEST-*.xml files, not archives
    assert runner.invoke(cli.main, ["--root", str(tmp_path / "ci"), "test-results", str(tmp_path / "x")]).exit_code == 1
    write(tmp_path / "ok/TEST-app.OkTest.xml", PASSING)
    assert runner.invoke(cli.main, ["--root", str(tmp_path / "ok"), "test-results"]).exit_code == 0
