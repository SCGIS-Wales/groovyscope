"""The tests a change reaches and the one Gradle command that compiles and runs them (`groovyscope verify`)."""

from __future__ import annotations

import json
import os
import zipfile
from collections.abc import Sequence
from pathlib import Path
from typing import Any

import pytest
from click.testing import CliRunner

from groovyscope import changes, cli, verify, workspace_init
from groovyscope.cli import common as cli_common
from groovyscope.cli import quality
from groovyscope.cli import quality as cli_quality

BUILD = """
plugins { id 'groovy' }
sourceSets { it { java.srcDir 'src/it/java' } }
tasks.register('integrationTest', Test) {
    testClassesDirs = sourceSets.it.output.classesDirs
}
task functional(type: Test) { classpath = sourceSets['functionalTest'].runtimeClasspath }
"""


def site(container: str, name: str, path: str) -> dict[str, Any]:
    return {"caller": {"container": container, "name": name, "location": {"path": path, "line": 1}}}


def test_source_sets_classes_and_test_paths() -> None:
    assert verify.source_set_of("src/it/java/app/StoreIT.java") == ("it", "java")
    assert verify.source_set_of("vars/build.groovy") is None
    assert verify.class_of("src/main/groovy/app/Store.groovy") == "app.Store"
    assert verify.class_of("Jenkinsfile") is None
    assert verify.is_test_path("src/integrationTest/groovy/app/A.groovy") and verify.is_test_path("lib/FooSpec.groovy")
    assert not verify.is_test_path("src/main/groovy/app/A.groovy") and not verify.is_test_path("src/org/acme/A.groovy")


def test_test_tasks_come_from_the_build_script() -> None:
    assert verify.test_tasks(BUILD) == {"it": "integrationTest", "functionalTest": "functional"}
    kts = 'tasks.register<Test>("slow") {\n testClassesDirs = sourceSets.named("slowTest").get().output.classesDirs }'
    assert verify.test_tasks(kts) == {"slowTest": "slow"}
    assert verify.task_for("test", {}) == "test" and verify.task_for("integrationTest", {}) == "integrationTest"


def test_affected_tests_and_the_gradle_command() -> None:
    reports = [
        {
            "tests": [
                site("app.StoreIT", "shouldRead", "src/it/java/app/StoreIT.java"),
                site("app.StoreSpec", "writes", "src/test/groovy/app/StoreSpec.groovy"),
                site("app.StoreSpec", "writes", "src/test/groovy/app/StoreSpec.groovy"),
                site("", "x", "Jenkinsfile"),
            ]
        }
    ]
    changed = ["src/main/groovy/app/Store.groovy", "src/test/groovy/app/OtherSpec.groovy", "build.gradle"]
    tests = verify.affected_tests(reports, changed, verify.is_test_path)
    assert [(t["className"], t["method"], t["sourceSet"]) for t in tests] == [
        ("app.StoreIT", "shouldRead", "it"),
        ("app.OtherSpec", "", "test"),
        ("app.StoreSpec", "writes", "test"),
    ]
    tasks = verify.test_tasks(BUILD)
    command = verify.gradle_command("./gradlew", verify.compile_tasks(changed), tests, tasks, ["test"])
    assert command == [
        "./gradlew",
        "compileGroovy",
        "compileTestGroovy",
        "test",
        "--tests",
        "app.OtherSpec",
        "--tests",
        "app.StoreSpec.writes",
    ]
    assert verify.not_run(tests, tasks, ["test"]) == {"integrationTest": ["app.StoreIT"]}
    assert verify.gradle_command("gradle", [], [], tasks, ["test"]) == []
    assert verify.quoted(["./gradlew", "test", "--tests", "app.A*"]) == "./gradlew test --tests 'app.A*'"


def test_method_filters_touched_test_methods_and_whole_classes() -> None:
    reports = [{"tests": [site("app.StoreSpec", "writes", "src/test/groovy/app/StoreSpec.groovy")]}]
    touched = [
        {"path": "src/test/groovy/app/OtherSpec.groovy", "symbol": "OtherSpec.reads", "kind": "method"},
        {"path": "src/test/groovy/app/OtherSpec.groovy", "symbol": "OtherSpec", "kind": "class"},
        {"path": "src/main/groovy/app/Store.groovy", "symbol": "Store.save", "kind": "method"},
    ]
    changed = ["src/test/groovy/app/OtherSpec.groovy", "src/test/groovy/app/StoreSpec.groovy"]
    tests = verify.affected_tests(reports, changed, verify.is_test_path, touched)
    assert [(t["className"], t["method"]) for t in tests] == [
        ("app.OtherSpec", "reads"),
        ("app.StoreSpec", ""),
        ("app.StoreSpec", "writes"),
    ]
    assert verify.filters_by_task(tests, {}, ["test"]) == {"test": ["app.OtherSpec.reads", "app.StoreSpec"]}
    assert verify.filters_by_task(tests, {}, ["integrationTest"]) == {}


def test_plan_reads_the_build_and_says_when_there_is_none(tmp_path: Path) -> None:
    reports = [{"tests": [site("app.StoreIT", "shouldRead", "src/it/java/app/StoreIT.java")]}]
    files = {tmp_path / "build.gradle": BUILD, tmp_path / "gradlew": "#!/bin/sh"}
    planned = verify.plan(tmp_path, reports, ["src/main/groovy/app/Store.groovy"], {}, limit=5, read=files.get)
    assert planned["count"] == 1 and planned["inCi"] == {"integrationTest": ["app.StoreIT"]}
    assert planned["command"] == "./gradlew compileGroovy" and planned["commandArgs"] == ["./gradlew", "compileGroovy"]
    run_it = verify.plan(tmp_path, reports, [], {"runTasks": ["integrationTest"]}, limit=5, read=files.get)
    assert run_it["command"] == "./gradlew integrationTest --tests app.StoreIT.shouldRead" and "inCi" not in run_it
    bare = verify.plan(tmp_path, reports, [], {}, limit=5, read=lambda _p: None)
    assert "command" not in bare and bare["note"].startswith("no build.gradle")
    (tmp_path / "build.gradle").write_text("x", encoding="utf-8")
    assert verify.read_text(tmp_path / "build.gradle") == "x" and verify.read_text(tmp_path / "none") is None


def test_gradle_output_is_summarised() -> None:
    output = "> Task :test\n\nStoreSpec > writes() FAILED\n    AssertionError\n\nFAILURE: Build failed\n"
    assert verify.failed_tests(output) == ["StoreSpec > writes()"]
    assert verify.tail(output, 2) == ["    AssertionError", "FAILURE: Build failed"]
    lines = verify.test_lines(
        {
            "count": 3,
            "items": [
                {"className": "app.StoreIT", "method": "shouldRead"},
                {"className": "app.OtherSpec", "method": ""},
            ],
            "command": "./gradlew test --tests app.OtherSpec",
            "inCi": {"integrationTest": ["app.StoreIT"]},
            "note": "n",
        }
    )
    assert lines == [
        "tests the change reaches (3): StoreIT.shouldRead, OtherSpec +1",
        "run: ./gradlew test --tests app.OtherSpec",
        "left to CI (integrationTest): StoreIT",
        "note: n",
    ]
    assert verify.test_lines({}) == []


@pytest.fixture
def review(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> dict[str, Any]:
    state: dict[str, Any] = {
        "result": {
            "base": "master",
            "mode": "branch",
            "verdict": "approve: nothing blocking on the changed lines",
            "counts": {"blocking": 0},
            "triage": {},
            "tests": {
                "count": 1,
                "items": [{"className": "app.S", "method": "t"}],
                "commandArgs": ["gradle", "test"],
                "command": "gradle test",
            },
        },
        "ran": [],
    }
    monkeypatch.setattr(
        cli_common, "daemon_socket_for", lambda _obj: (tmp_path / "s.sock", {"verify": {"timeoutSeconds": 9}})
    )
    monkeypatch.setattr(cli_common, "workspace_tools", lambda *_a: {"review_changes": lambda *_a: state["result"]})

    def runner(command: Sequence[str], cwd: Path, timeout: int) -> tuple[int, str]:
        state["ran"].append((list(command), timeout))
        if "--init-script" in command:
            state["script"] = Path(command[command.index("--init-script") + 1]).read_text(encoding="utf-8")
        for name, text in state.get("reports", {}).items():
            (cwd / name).parent.mkdir(parents=True, exist_ok=True)
            (cwd / name).write_text(text, encoding="utf-8")
        return state.get("build", (0, "BUILD SUCCESSFUL"))

    monkeypatch.setattr(cli_quality, "_VERIFY_RUNNER", runner)
    return state


def test_verify_runs_the_planned_build_once(review: dict[str, Any], tmp_path: Path) -> None:
    runner = CliRunner()
    passed = runner.invoke(cli.main, ["--root", str(tmp_path), "verify"])
    assert passed.exit_code == 0, passed.output
    assert passed.output.startswith("verify against master: approve") and "build: passed" in passed.output
    assert review["ran"] == [(["gradle", "test"], 9)]
    dry = runner.invoke(cli.main, ["--root", str(tmp_path), "verify", "--dry-run"])
    assert "dry run: nothing was built" in dry.output and len(review["ran"]) == 1
    review["build"] = (1, "S > t() FAILED\nFAILURE: Build failed")
    failed = runner.invoke(cli.main, ["--root", str(tmp_path), "verify", "--json"])
    payload = json.loads(failed.output)
    assert failed.exit_code == 1 and payload["build"]["failed"] == ["S > t()"]
    assert payload["build"]["tail"] == ["S > t() FAILED", "FAILURE: Build failed"]
    assert payload["build"]["results"]["reports"] == 0 and "coverage" not in payload
    review["build"] = (2, "compile error: x")
    broken = runner.invoke(cli.main, ["--root", str(tmp_path), "verify"])
    assert "build: FAILED (exit 2)" in broken.output and "  compile error: x" in broken.output
    review["result"] = {"error": "no git"}
    assert runner.invoke(cli.main, ["--root", str(tmp_path), "verify"]).exit_code == 1


REPORT = """<testsuite name="app.S" tests="2" failures="1" errors="0">
  <testcase name="t" classname="app.S"><failure message="expected 2">x
\tat app.Store.save(Store.groovy:7)
</failure></testcase>
  <testcase name="u" classname="app.S"/>
</testsuite>"""
JACOCO = """<report name="r"><package name="app">
  <class name="app/Store" sourcefilename="Store.groovy">
    <method name="save" desc="()V" line="3"><counter type="LINE" missed="2" covered="0"/></method>
    <method name="load" desc="()V" line="9"><counter type="LINE" missed="1" covered="0"/></method>
    <counter type="LINE" missed="3" covered="0"/>
  </class>
  <sourcefile name="Store.groovy"><counter type="LINE" missed="3" covered="0"/></sourcefile>
  <counter type="LINE" missed="3" covered="0"/>
</package><counter type="LINE" missed="3" covered="0"/></report>"""


def test_verify_reads_the_reports_the_run_wrote(review: dict[str, Any], tmp_path: Path) -> None:
    stale = tmp_path / "old/build/test-results/test/TEST-app.Old.xml"
    stale.parent.mkdir(parents=True)
    stale.write_text(REPORT.replace("app.S", "app.Old"), encoding="utf-8")
    os.utime(stale, (1, 1))
    review["result"]["tests"]["commandArgs"] = ["gradle", "test", "--tests", "app.S.t"]
    review["reports"] = {"build/test-results/test/TEST-app.S.xml": REPORT}
    review["build"] = (1, "FAILURE: Build failed")
    out = CliRunner().invoke(cli.main, ["--root", str(tmp_path), "verify"])
    assert out.exit_code == 1, out.output
    assert "2 test(s) in 1 report(s): 1 failed" in out.output
    assert "  failure: app.S.t: expected 2 at Store.groovy:7 in app.Store.save" in out.output
    command = review["ran"][0][0]
    assert command[:2] == ["gradle", "--init-script"] and command[3:] == ["test", "--tests", "app.S.t"]
    assert "failOnNoMatchingTests = false" in review["script"] and not Path(command[2]).exists()
    review["reports"] = {"build/test-results/test/TEST-app.S.xml": '<testsuite name="app.S" tests="0"/>'}
    review["build"] = (0, "BUILD SUCCESSFUL")
    none = CliRunner().invoke(cli.main, ["--root", str(tmp_path), "verify"])
    assert "the --tests filters matched no test" in none.output
    review["reports"] = {}
    for report in (tmp_path / "build/test-results/test").iterdir():
        report.unlink()
    nothing = CliRunner().invoke(cli.main, ["--root", str(tmp_path), "verify"])
    assert "no test report was written" in nothing.output


def test_verify_coverage_lists_changed_methods_no_test_ran(
    review: dict[str, Any], tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    review["result"]["tests"]["coverageTask"] = "jacocoTestReport"
    review["reports"] = {"build/reports/jacoco/test/jacocoTestReport.xml": JACOCO}
    (tmp_path / "src/main/groovy/app").mkdir(parents=True)
    (tmp_path / "src/main/groovy/app/Store.groovy").write_text("x", encoding="utf-8")
    change = {"base": "master", "mode": "branch", "files": [
        {"path": "src/main/groovy/app/Store.groovy", "status": "modified", "ranges": [[2, 4]]}]}  # fmt: skip
    monkeypatch.setattr(changes, "changed_files", lambda *_a, **_k: change)
    monkeypatch.setattr(cli_common, "daemon_socket_for", lambda _obj: (tmp_path / "s.sock", {"reports": {}}))
    out = CliRunner().invoke(cli.main, ["--root", str(tmp_path), "verify", "--coverage"])
    assert review["ran"][0][0] == ["gradle", "test", "jacocoTestReport"]
    assert "coverage: 1 changed method(s) no test executed" in out.output
    assert "  src/main/groovy/app/Store.groovy:3: Store.save" in out.output
    change["files"][0]["ranges"] = [[20, 30]]
    payload = json.loads(
        CliRunner().invoke(cli.main, ["--root", str(tmp_path), "verify", "--coverage", "--json"]).output
    )
    assert payload["coverage"]["count"] == 0 and payload["tests"]["command"] == "gradle test jacocoTestReport"
    assert (
        "every changed method" in CliRunner().invoke(cli.main, ["--root", str(tmp_path), "verify", "--coverage"]).output
    )
    (tmp_path / "build/reports/jacoco/test/jacocoTestReport.xml").write_text("<html/>", encoding="utf-8")
    review["reports"] = {}
    broken = CliRunner().invoke(cli.main, ["--root", str(tmp_path), "verify", "--coverage"])
    assert "coverage: " in broken.output and "changed method" not in broken.output
    (tmp_path / "build/reports/jacoco/test/jacocoTestReport.xml").unlink()
    gone = CliRunner().invoke(cli.main, ["--root", str(tmp_path), "verify", "--coverage"])
    assert "coverage: no JaCoCo report at" in gone.output
    del review["result"]["tests"]["coverageTask"]
    plain = CliRunner().invoke(cli.main, ["--root", str(tmp_path), "verify", "--coverage"])
    assert "the build does not apply JaCoCo" in plain.output and review["ran"][-1][0] == ["gradle", "test"]


def test_verify_failed_runs_the_last_failures_again(review: dict[str, Any], tmp_path: Path) -> None:
    report = tmp_path / "store/build/test-results/integrationTest/TEST-app.S.xml"
    report.parent.mkdir(parents=True)
    report.write_text(REPORT.replace('name="t"', 'name="t()[1]"'), encoding="utf-8")
    surefire = tmp_path / "target/surefire-reports/TEST-app.M.xml"
    surefire.parent.mkdir(parents=True)
    surefire.write_text(REPORT.replace("app.S", "app.M"), encoding="utf-8")
    (tmp_path / "gradlew").write_text("#!/bin/sh", encoding="utf-8")
    runner = CliRunner()
    dry = runner.invoke(cli.main, ["--root", str(tmp_path), "verify", "--failed", "--dry-run"])
    assert dry.exit_code == 0 and "2 failed test(s) in 2 report(s)" in dry.output
    assert "run: ./gradlew integrationTest --tests app.S.t" in dry.output and "dry run" in dry.output
    assert "run it with its own build: app.M.t" in dry.output and review["ran"] == []
    review["build"] = (1, "FAILURE")
    ran = runner.invoke(cli.main, ["--root", str(tmp_path), "verify", "--failed", "--json"])
    payload = json.loads(ran.output)
    assert ran.exit_code == 1 and payload["command"] == ["./gradlew", "integrationTest", "--tests", "app.S.t"]
    assert payload["unplaced"] == ["app.M.t"] and payload["build"]["exitCode"] == 1
    archive = tmp_path / "ci.zip"
    with zipfile.ZipFile(archive, "w") as zipped:
        zipped.writestr("m/build/test-results/test/TEST-app.Z.xml", REPORT.replace("app.S", "app.Z"))
    from_ci = runner.invoke(cli.main, ["--root", str(tmp_path), "verify", "--failed", "--results", str(archive)])
    assert "run: ./gradlew test --tests app.Z.t" in from_ci.output
    assert runner.invoke(cli.main, ["--root", str(tmp_path), "verify", "--results", str(archive)]).exit_code == 2
    clean = tmp_path / "clean"
    clean.mkdir()
    none = runner.invoke(cli.main, ["--root", str(clean), "verify", "--failed"])
    assert none.exit_code == 0 and none.output.startswith("0 failed test(s) in 0 report(s)")
    (clean / ".groovyscope.yaml").write_text("verify: [", encoding="utf-8")
    assert runner.invoke(cli.main, ["--root", str(clean), "verify", "--failed"]).exit_code == 1


def test_rerun_and_coverage_helpers() -> None:
    assert verify.method_name("shouldRead()") == "shouldRead" and verify.method_name("reads [x: 1, #0]") == "reads"
    assert verify.method_name("param[2]") == "param" and verify.method_name("writes a row") == "writes a row"
    assert verify.task_of_report("a.zip!m/build/test-results/test/TEST-x.xml") == "test"
    assert verify.task_of_report("target/surefire-reports/TEST-x.xml") is None
    failure = {"className": "app.S", "name": "", "report": "build/test-results/test/TEST-app.S.xml"}
    assert verify.rerun_command("gradle", [failure, failure]) == (["gradle", "test", "--tests", "app.S"], [])
    assert verify.rerun_command("gradle", []) == ([], [])
    assert verify.with_coverage(["gradle", "test"], "jacocoTestReport") == ["gradle", "test", "jacocoTestReport"]
    assert verify.with_coverage(["gradle", "jacocoTestReport"], "jacocoTestReport") == ["gradle", "jacocoTestReport"]
    assert verify.with_coverage([], "jacocoTestReport") == [] and verify.with_init_script(["g", "test"], Path("i")) == [
        "g",
        "test",
    ]
    keep = verify.written_since(10.0, lambda label: 5.0 if label == "old" else 20.0)
    assert keep("new") and not keep("old")

    def missing(_label: str) -> float:
        raise OSError

    assert not verify.written_since(0.0, missing)("x")
    gaps = [
        {"path": "a", "line": 3, "kind": "method", "status": "uncovered", "name": "A.m"},
        {"path": "a", "line": 3, "kind": "method", "status": "partial", "name": "A.p"},
        {"path": "a", "line": 3, "kind": "class", "status": "uncovered", "name": "A"},
        {"path": "b", "line": 3, "kind": "method", "status": "uncovered", "name": "B.m"},
    ]
    assert [g["name"] for g in verify.uncovered_in_change(gaps, {"a": [[1, 5]]})] == ["A.m"]


def test_plan_names_the_coverage_task_when_the_build_uses_jacoco(tmp_path: Path) -> None:
    files = {tmp_path / "build.gradle": "plugins { id 'jacoco' }"}
    planned = verify.plan(tmp_path, [], [], {"coverageTask": "cov"}, limit=5, read=files.get)
    assert planned["coverageTask"] == "cov"
    assert verify.plan(tmp_path, [], [], {}, limit=5, read=files.get)["coverageTask"] == "jacocoTestReport"
    assert "coverageTask" not in verify.plan(tmp_path, [], [], {}, limit=5, read={tmp_path / "build.gradle": "x"}.get)


def test_run_build_reports_missing_tools_and_timeouts(tmp_path: Path) -> None:
    assert cli.run_build(["/nonexistent/gradlew"], tmp_path, 5)[0] == -1
    assert cli.run_build(["sleep", "5"], tmp_path, 1)[0] == -2
    assert cli.run_build(["sh", "-c", "echo hi; exit 3"], tmp_path, 5) == (3, "hi\n")


def test_source_sets_join_the_roots_unless_turned_off(tmp_path: Path) -> None:
    for folder in ("src/main/groovy", "src/it/java", "src/integrationTest/groovy", "src/docs"):
        (tmp_path / folder).mkdir(parents=True)
    assert workspace_init.source_sets(tmp_path) == [
        "src/integrationTest/groovy",
        "src/it/java",
        "src/main/groovy",
    ]
    assert workspace_init.source_sets(tmp_path / "none") == []
    configured = {"sourceRoots": ["src/main/groovy", "vars"]}
    assert workspace_init.with_source_sets(tmp_path, configured)["sourceRoots"] == [
        "src/main/groovy",
        "vars",
        "src/integrationTest/groovy",
        "src/it/java",
    ]
    off = {"sourceRoots": ["vars"], "sourceSets": False}
    assert workspace_init.with_source_sets(tmp_path, off) == off


def test_verify_coverage_fails_under_the_threshold_on_a_fresh_report(
    review: dict[str, Any], tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    review["result"]["tests"]["coverageTask"] = "jacocoTestReport"
    review["result"]["files"] = [{"path": "src/main/groovy/app/Store.groovy", "status": "modified"}]
    (tmp_path / "src/main/groovy/app").mkdir(parents=True)
    (tmp_path / "src/main/groovy/app/Store.groovy").write_text("x", encoding="utf-8")
    review["reports"] = {"build/reports/jacoco/test/jacocoTestReport.xml": JACOCO}  # written by the run: fresh
    change = {"base": "master", "mode": "branch", "files": [
        {"path": "src/main/groovy/app/Store.groovy", "status": "modified", "ranges": [[2, 4]]}]}  # fmt: skip
    monkeypatch.setattr(changes, "changed_files", lambda *_a, **_k: change)
    out = CliRunner().invoke(cli.main, ["--root", str(tmp_path), "verify", "--coverage"])
    assert out.exit_code == 1, out.output
    assert "coverage: changed classes at 80% or above: no, 1 under (workspace 0%)" in out.output
    assert "  src/main/groovy/app/Store.groovy: 0%" in out.output
    payload = json.loads(
        CliRunner().invoke(cli.main, ["--root", str(tmp_path), "verify", "--coverage", "--json"]).output
    )
    assert payload["threshold"]["verdict"] == "below"

    review["result"]["coverage"] = {"verdict": "stale", "note": "the report predates the change"}
    plain = CliRunner().invoke(cli.main, ["--root", str(tmp_path), "verify"])
    assert plain.exit_code == 0 and "coverage: the report predates the change" in plain.output


def test_integration_tests_left_behind_by_a_signature_change_are_gs049() -> None:
    assert verify.is_integration_path("src/it/groovy/app/StoreIT.groovy")
    assert verify.is_integration_path("src/integrationTest/java/app/Store.java")
    assert verify.is_integration_path("src/test/groovy/app/StoreIT.groovy")
    assert not verify.is_integration_path("src/test/groovy/app/StoreSpec.groovy")

    def site(path: str) -> dict[str, Any]:
        return {"caller": {"location": {"path": path}}}

    declaration = {"symbol": "Store.save", "path": "src/main/groovy/app/Store.groovy", "lines": [5, 9]}
    report = {
        "directCallers": [site("src/main/groovy/app/Shop.groovy")],
        "tests": [site("src/test/groovy/app/StoreSpec.groovy")],
        "integrationTests": [site("src/it/groovy/app/StoreIT.groovy")],
    }
    ranges = {"src/main/groovy/app/Store.groovy": [[5, 5]]}
    (finding,) = verify.integration_out_of_line([(declaration, report)], ranges)
    assert finding["code"] == "GS049" and finding["severity"] == "error" and finding["line"] == 5
    assert "src/it/groovy/app/StoreIT.groovy" in finding["message"]
    both = {**ranges, "src/it/groovy/app/StoreIT.groovy": [[3, 4]]}
    assert verify.integration_out_of_line([(declaration, report)], both) == []  # the branch updated it too
    body_only = {"src/main/groovy/app/Store.groovy": [[7, 8]]}
    assert verify.integration_out_of_line([(declaration, report)], body_only) == []
    caller = {"name": "stores", "container": "app.StoreIT", "location": {"path": "src/it/groovy/app/StoreIT.groovy"}}
    tests = verify.affected_tests([{"tests": [], "integrationTests": [{"caller": caller}]}], [], verify.is_test_path)
    assert tests == [
        {"className": "app.StoreIT", "method": "stores", "path": "src/it/groovy/app/StoreIT.groovy", "sourceSet": "it"}
    ]


def test_verify_integration_runs_the_integration_tasks_or_says_what_they_need(tmp_path: Path) -> None:
    added, needs = quality.with_integration({"verify": {"runTasks": ["test"]}})
    assert added["verify"]["runTasks"] == ["test", "integrationTest"] and needs == []
    kept, needs = quality.with_integration({"verify": {"integrationNeeds": ["credentials"]}})
    assert kept == {"verify": {"integrationNeeds": ["credentials"]}} and needs == ["credentials"]
    plan_result = {"inCi": {"integrationTest": ["app.StoreIT"]}}
    assert quality.held_back_lines(["credentials"], plan_result) == [
        "integration tests not run here (they need credentials): name them in the change"
    ]
    assert quality.held_back_lines([], plan_result) == [] and quality.held_back_lines(["x"], {}) == []


def test_verify_integration_flag_reaches_the_review(
    review: dict[str, Any], tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    seen: list[dict[str, Any]] = []

    def tools(_obj: Any, _socket: Any, effective: dict[str, Any]) -> dict[str, Any]:
        seen.append(effective)
        return {"review_changes": lambda *_a: review["result"]}

    monkeypatch.setattr(cli_common, "workspace_tools", tools)
    review["result"]["tests"]["inCi"] = {"integrationTest": ["app.StoreIT"]}
    out = CliRunner().invoke(cli.main, ["--root", str(tmp_path), "verify", "--integration", "--dry-run"])
    assert out.exit_code == 0, out.output
    assert seen[-1]["verify"]["runTasks"] == ["test", "integrationTest"]
