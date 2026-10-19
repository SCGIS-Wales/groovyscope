"""The tests a change reaches and the one Gradle command that compiles and runs them (`groovyscope verify`)."""

from __future__ import annotations

import json
from collections.abc import Sequence
from pathlib import Path
from typing import Any

import pytest
from click.testing import CliRunner

from groovyscope import cli, verify, workspace_init

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
        "app.StoreSpec",
    ]
    assert verify.not_run(tests, tasks, ["test"]) == {"integrationTest": ["app.StoreIT"]}
    assert verify.gradle_command("gradle", [], [], tasks, ["test"]) == []
    assert verify.quoted(["./gradlew", "test", "--tests", "app.A*"]) == "./gradlew test --tests 'app.A*'"


def test_plan_reads_the_build_and_says_when_there_is_none(tmp_path: Path) -> None:
    reports = [{"tests": [site("app.StoreIT", "shouldRead", "src/it/java/app/StoreIT.java")]}]
    files = {tmp_path / "build.gradle": BUILD, tmp_path / "gradlew": "#!/bin/sh"}
    planned = verify.plan(tmp_path, reports, ["src/main/groovy/app/Store.groovy"], {}, limit=5, read=files.get)
    assert planned["count"] == 1 and planned["inCi"] == {"integrationTest": ["app.StoreIT"]}
    assert planned["command"] == "./gradlew compileGroovy" and planned["commandArgs"] == ["./gradlew", "compileGroovy"]
    run_it = verify.plan(tmp_path, reports, [], {"runTasks": ["integrationTest"]}, limit=5, read=files.get)
    assert run_it["command"] == "./gradlew integrationTest --tests app.StoreIT" and "inCi" not in run_it
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
    monkeypatch.setattr(cli, "daemon_socket_for", lambda _obj: (tmp_path / "s.sock", {"verify": {"timeoutSeconds": 9}}))
    monkeypatch.setattr(cli, "workspace_tools", lambda *_a: {"review_changes": lambda *_a: state["result"]})

    def runner(command: Sequence[str], _cwd: Path, timeout: int) -> tuple[int, str]:
        state["ran"].append((list(command), timeout))
        return state.get("build", (0, "BUILD SUCCESSFUL"))

    monkeypatch.setattr(cli, "_VERIFY_RUNNER", runner)
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
    assert failed.exit_code == 1 and payload["build"] == {
        "exitCode": 1,
        "failed": ["S > t()"],
        "tail": ["S > t() FAILED", "FAILURE: Build failed"],
    }
    review["build"] = (2, "compile error: x")
    broken = runner.invoke(cli.main, ["--root", str(tmp_path), "verify"])
    assert "build: FAILED (exit 2)" in broken.output and "  compile error: x" in broken.output
    review["result"] = {"error": "no git"}
    assert runner.invoke(cli.main, ["--root", str(tmp_path), "verify"]).exit_code == 1


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
