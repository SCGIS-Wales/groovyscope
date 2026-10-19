"""scripts/bundle_jar.py copies a JAR into the package and writes a verifiable manifest."""

from __future__ import annotations

import importlib.util
import json
import zipfile
from pathlib import Path
from typing import Any

import pytest

from groovyscope.jar import locate_jar

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "bundle_jar.py"


def load_script() -> Any:
    spec = importlib.util.spec_from_file_location("bundle_jar", SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.mark.skipif(not SCRIPT.is_file(), reason="repository checkout required")
def test_bundle_writes_manifest_that_locate_jar_accepts(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    script = load_script()
    jar = tmp_path / "groovyscope-engine-1.2.3-all.jar"
    jar.write_bytes(b"jar bytes")
    stale = tmp_path / "out" / "groovyscope-engine-0.0.1-all.jar"
    stale.parent.mkdir()
    stale.write_bytes(b"old")
    assert script.main(["--jar", str(jar), "--jar-dir", str(stale.parent)]) == 0
    assert not stale.exists()
    manifest = json.loads((stale.parent / "_manifest.json").read_text(encoding="utf-8"))
    assert manifest["version"] == "1.2.3"
    assert locate_jar({}, stale.parent)["verified"] is True
    assert "bundled groovyscope-engine-1.2.3-all.jar" in capsys.readouterr().out


@pytest.mark.skipif(not SCRIPT.is_file(), reason="repository checkout required")
def test_bundle_errors(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    script = load_script()
    assert script.main(["--jar", str(tmp_path / "missing.jar"), "--jar-dir", str(tmp_path)]) == 1
    odd = tmp_path / "engine.jar"
    odd.write_bytes(b"x")
    assert script.main(["--jar", str(odd), "--jar-dir", str(tmp_path)]) == 1
    assert "cannot parse a version" in capsys.readouterr().err
    assert script.main(["--jar", str(odd), "--jar-dir", str(tmp_path), "--version", "0.0.0"]) == 0
    assert script.newest_jar(tmp_path / "nowhere") is None


@pytest.mark.skipif(not SCRIPT.is_file(), reason="repository checkout required")
def test_bundle_records_the_embedded_codenarc_version(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    script = load_script()
    jar = tmp_path / "groovyscope-engine-1.2.3-all.jar"
    with zipfile.ZipFile(jar, "w") as archive:
        archive.writestr("org/groovyscope/engine/engine-version.properties", "version=1.2.3\ncodenarc=4.0.0\n")
    out = tmp_path / "out"
    assert script.main(["--jar", str(jar), "--jar-dir", str(out)]) == 0
    manifest = json.loads((out / "_manifest.json").read_text(encoding="utf-8"))
    assert manifest["codenarc"] == "4.0.0"
    assert locate_jar({}, out)["codenarc"] == "4.0.0"
    assert "CodeNarc 4.0.0" in capsys.readouterr().out
    assert script.embedded_versions(tmp_path / "missing.jar") == {}


def fake_repo(root: Path, python_version: str, engine_version: str) -> Path:
    (root / "python").mkdir(parents=True)
    (root / "python" / "pyproject.toml").write_text(f'[project]\nname = "groovyscope"\nversion = "{python_version}"\n')
    (root / "engine" / "build" / "libs").mkdir(parents=True)
    (root / "engine" / "gradle.properties").write_text(f"# x-release-please-start-version\nversion={engine_version}\n")
    return root


@pytest.mark.skipif(not SCRIPT.is_file(), reason="repository checkout required")
def test_bundle_picks_the_jar_of_the_project_version_not_the_newest(tmp_path: Path) -> None:
    script = load_script()
    repo = fake_repo(tmp_path / "repo", "1.2.3", "1.2.3")
    libs = repo / "engine" / "build" / "libs"
    with zipfile.ZipFile(libs / "groovyscope-engine-1.2.3-all.jar", "w") as archive:
        archive.writestr("org/groovyscope/engine/engine-version.properties", "version=1.2.3\n")
    (libs / "groovyscope-engine-9.9.9-all.jar").write_bytes(b"newer but stale")
    out = tmp_path / "out"
    assert script.main(["--repo", str(repo), "--jar-dir", str(out)]) == 0
    assert json.loads((out / "_manifest.json").read_text(encoding="utf-8"))["jar"] == "groovyscope-engine-1.2.3-all.jar"


@pytest.mark.skipif(not SCRIPT.is_file(), reason="repository checkout required")
def test_bundle_refuses_versions_that_disagree(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    script = load_script()
    split = fake_repo(tmp_path / "split", "1.2.3", "1.2.4")
    assert script.main(["--repo", str(split), "--jar-dir", str(tmp_path / "out")]) == 1
    assert "1.2.3" in capsys.readouterr().err
    repo = fake_repo(tmp_path / "repo", "1.2.3", "1.2.3")
    with zipfile.ZipFile(repo / "engine" / "build" / "libs" / "groovyscope-engine-1.2.3-all.jar", "w") as archive:
        archive.writestr("org/groovyscope/engine/engine-version.properties", "version=1.2.2\n")
    assert script.main(["--repo", str(repo), "--jar-dir", str(tmp_path / "out")]) == 1
    assert "1.2.2" in capsys.readouterr().err
    assert script.main(["--repo", str(fake_repo(tmp_path / "empty", "2.0.0", "2.0.0")), "--jar-dir", str(tmp_path)]) == 1
