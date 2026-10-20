"""`groovyscope classpath`, `groovyscope init`, `groovyscope profile install` and the Kiro diff format."""

from __future__ import annotations

import os
import stat
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

import pytest
from click.testing import CliRunner

from groovyscope import classpath, cli, trust, workspace_init
from groovyscope import classpath as classpath_export
from groovyscope import daemon as daemon_lifecycle
from groovyscope.kiro import text_diff


def runner_for(output: str, code: int = 0, *, write_maven: Path | None = None) -> Any:
    calls: list[list[str]] = []

    def run(command: Sequence[str], cwd: Path, environ: Mapping[str, str], timeout: int) -> tuple[int, str]:
        calls.append(list(command))
        if write_maven is not None:
            listing = write_maven / classpath.MAVEN_OUTPUT
            listing.parent.mkdir(parents=True, exist_ok=True)
            listing.write_text(os.pathsep.join([str(cwd / "lib" / "a.jar"), "/nowhere/b.jar"]), encoding="utf-8")
        return (code, output)

    run.calls = calls  # type: ignore[attr-defined]
    return run


def test_gradle_export_uses_the_wrapper_and_parses_marked_lines(tmp_path: Path) -> None:
    (tmp_path / "settings.gradle").write_text("", encoding="utf-8")
    wrapper = tmp_path / "gradlew"
    wrapper.write_text("#!/bin/sh\n", encoding="utf-8")
    wrapper.chmod(wrapper.stat().st_mode | stat.S_IXUSR)
    jar = tmp_path / "lib" / "a.jar"
    jar.parent.mkdir()
    jar.write_bytes(b"x")
    output = f"noise\nGROOVYSCOPE_CLASSPATH {jar}\nGROOVYSCOPE_CLASSPATH {jar}\nGROOVYSCOPE_CLASSPATH /nowhere/b.jar\n"
    run = runner_for(output)
    result = classpath.export(tmp_path, {}, runner=run, which=lambda _n: None)
    assert result["tool"] == "gradle" and result["paths"] == [str(jar)] and result["missing"] == ["/nowhere/b.jar"]
    assert run.calls[0][0] == str(wrapper) and "--no-configuration-cache" in run.calls[0]
    assert Path(run.calls[0][run.calls[0].index("--init-script") + 1]).is_file()
    target = tmp_path / ".groovyscope" / "classpath.txt"
    classpath.write_file(target, result)
    assert (
        target.read_text(encoding="utf-8").splitlines()[1] == "# produced by: gradlew -q --no-configuration-cache "
        f"--init-script {classpath.init_script()} groovyscopeClasspath"
    )
    assert classpath.read_file(target) == [str(jar)]
    assert classpath.read_file(tmp_path / "missing.txt") == []


def test_maven_export_and_failures(tmp_path: Path) -> None:
    (tmp_path / "pom.xml").write_text("<project/>", encoding="utf-8")
    (tmp_path / "lib").mkdir()
    (tmp_path / "lib" / "a.jar").write_bytes(b"x")
    (tmp_path / "target" / "classes").mkdir(parents=True)
    run = runner_for("", write_maven=tmp_path)
    result = classpath.export(tmp_path, {}, runner=run, which=lambda n: "/usr/bin/mvn" if n == "mvn" else None)
    assert result["tool"] == "maven" and str(tmp_path / "lib" / "a.jar") in result["paths"]
    assert str(tmp_path / "target" / "classes") in result["paths"]
    assert not (tmp_path / classpath.MAVEN_OUTPUT).exists()
    with pytest.raises(ValueError, match="failed"):
        classpath.export(tmp_path, {}, runner=runner_for("BUILD FAILURE", 1), which=lambda _n: "/usr/bin/mvn")
    with pytest.raises(ValueError, match="not installed"):
        classpath.export(tmp_path, {}, runner=run, which=lambda _n: None)
    empty = tmp_path / "empty"
    empty.mkdir()
    with pytest.raises(ValueError, match="no Gradle or Maven build"):
        classpath.export(empty, {}, runner=run)
    assert classpath.default_runner(["/nonexistent/tool"], tmp_path, {}, 5)[0] == -1
    assert classpath.default_runner(["sleep", "5"], tmp_path, {"PATH": os.environ["PATH"]}, 1)[0] == -1


def test_classpath_command_requires_trust_and_feeds_the_engine(
    runner: CliRunner, tmp_path: Path, clean_env: dict[str, str], isolated_home: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    (tmp_path / "settings.gradle").write_text("", encoding="utf-8")
    refused = runner.invoke(cli.main, ["--root", str(tmp_path), "classpath"])
    assert refused.exit_code == 1 and "groovyscope trust" in refused.output
    trust.set_trusted(tmp_path, isolated_home, trusted=True)
    jar = tmp_path / "dep.jar"
    jar.write_bytes(b"x")

    def fake_export(root: Path, environ: Mapping[str, str], **_kwargs: Any) -> classpath.Export:
        return {"tool": "gradle", "command": ["gradle", "x"], "paths": [str(jar)], "missing": ["/gone.jar"]}

    monkeypatch.setattr(classpath_export, "export", fake_export)
    stopped: list[Path] = []
    monkeypatch.setattr(daemon_lifecycle, "probe", lambda _s, _t=5.0: {"engine": "x"})
    monkeypatch.setattr(daemon_lifecycle, "stop", lambda s: stopped.append(s) or True)
    done = runner.invoke(cli.main, ["--root", str(tmp_path), "classpath"])
    assert done.exit_code == 0, done.output
    assert "1 classpath entries from gradle written to .groovyscope/classpath.txt" in done.output
    assert "1 listed path(s) do not exist" in done.output and stopped
    view = cli.engine_view(
        {"root": tmp_path},
        {
            "lint": {"ruleset": None, "jenkinsRuleset": None},
            "workspace": {"classpath": ["/a"], "classpathFile": ".groovyscope/classpath.txt"},
        },
    )
    assert view["workspace"]["classpath"] == ["/a", str(jar)]

    def failing(*_a: Any, **_k: Any) -> classpath.Export:
        msg = "gradlew failed (exit 1)"
        raise ValueError(msg)

    monkeypatch.setattr(classpath_export, "export", failing)
    assert runner.invoke(cli.main, ["--root", str(tmp_path), "classpath"]).exit_code == 1


def test_init_detects_layout_and_keeps_user_settings(
    runner: CliRunner, tmp_path: Path, clean_env: dict[str, str]
) -> None:
    (tmp_path / "src" / "main" / "groovy").mkdir(parents=True)
    (tmp_path / "src" / "test" / "groovy").mkdir(parents=True)
    (tmp_path / "build.gradle").write_text(
        "dependencies {\n    implementation 'org.apache.groovy:groovy:5.1.2'\n}\n", encoding="utf-8"
    )
    dry = runner.invoke(cli.main, ["--root", str(tmp_path), "init", "--dry-run"])
    assert dry.exit_code == 0 and "+    - src/main/groovy" not in dry.output and "src/main/groovy" in dry.output
    assert not (tmp_path / ".groovyscope.yaml").exists()
    runner.invoke(cli.main, ["--root", str(tmp_path), "init"])
    written = (tmp_path / ".groovyscope.yaml").read_text(encoding="utf-8")
    assert "version: '5.1'" in written and "src/test/groovy" in written
    (tmp_path / ".groovyscope.yaml").write_text(
        "groovy: {version: '6.0'}\nlint: {failOnPriority: 1}\n", encoding="utf-8"
    )
    again = runner.invoke(cli.main, ["--root", str(tmp_path), "init"])
    merged = (tmp_path / ".groovyscope.yaml").read_text(encoding="utf-8")
    assert "version: '6.0'" in merged and "failOnPriority: 1" in merged and "sourceRoots" in merged
    assert (tmp_path / ".groovyscope.yaml.bak").is_file() and again.exit_code == 0
    assert "already has these settings" in runner.invoke(cli.main, ["--root", str(tmp_path), "init"]).output
    (tmp_path / ".groovyscope.yaml").write_text("- a list\n", encoding="utf-8")
    assert runner.invoke(cli.main, ["--root", str(tmp_path), "init"]).exit_code == 1


def test_groovy_version_detection_variants(tmp_path: Path) -> None:
    (tmp_path / "pom.xml").write_text(
        "<dependency><groupId>org.apache.groovy</groupId><artifactId>groovy</artifactId>"
        "<version>4.0.24</version></dependency>",
        encoding="utf-8",
    )
    assert workspace_init.detect_groovy_version(tmp_path) == "4.0"
    assert workspace_init.detected(tmp_path)["groovy"] == {"version": "4.0", "compat": ["4.0"]}
    other = tmp_path / "kts"
    other.mkdir()
    (other / "build.gradle.kts").write_text('implementation("org.apache.groovy:groovy-all:6.0.0")', encoding="utf-8")
    assert workspace_init.detect_groovy_version(other) == "6.0"
    (other / "src").mkdir()
    assert workspace_init.detect_roots(other) == ["src"]
    assert workspace_init.detect_groovy_version(tmp_path / "none") is None


def test_profile_install(runner: CliRunner, tmp_path: Path, isolated_home: Path) -> None:
    good = tmp_path / "mine.yaml"
    good.write_text("name: mine\n", encoding="utf-8")
    result = runner.invoke(cli.main, ["profile", "install", str(good)])
    target = isolated_home / ".groovyscope" / "profiles" / "mine.yaml"
    assert result.exit_code == 0 and target.read_text(encoding="utf-8") == "name: mine\n"
    assert stat.S_IMODE(target.stat().st_mode) == 0o600
    assert runner.invoke(cli.main, ["profile", "install", str(good)]).exit_code == 1
    assert runner.invoke(cli.main, ["profile", "install", "--force", str(good)]).exit_code == 0
    bad = tmp_path / "bad.yaml"
    bad.write_text("name: bad\nunknown: 1\n", encoding="utf-8")
    assert runner.invoke(cli.main, ["profile", "install", str(bad)]).exit_code == 1


def test_diff_marks_a_missing_final_newline() -> None:
    diff = text_diff('{"a": 1}', '{"a": 1, "b": 2}\n', "x.json")
    assert '-{"a": 1}\n\\ No newline at end of file\n+{"a": 1, "b": 2}\n' in diff
    assert text_diff("same\n", "same\n", "x") == ""
