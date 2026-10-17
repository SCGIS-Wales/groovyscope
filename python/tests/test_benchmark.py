"""The bundled benchmark corpus: manifest, unpacking, refresh and the zip-slip guard."""

from __future__ import annotations

import json
import zipfile
from pathlib import Path

import pytest
import yaml

from groovyscope import benchmark


def test_manifest_matches_the_zip() -> None:
    found = benchmark.manifest()
    assert found is not None
    assert found["name"] == "CodeNarc" and found["version"] == "4.0.0"
    assert found["roots"] == ["src/main/groovy", "src/main/java"]


def test_corpus_root_honours_the_override(tmp_path: Path) -> None:
    assert benchmark.corpus_root(tmp_path, {}) == tmp_path / ".groovyscope" / "benchmark" / "codenarc"
    override = {"GROOVYSCOPE_BENCHMARK_DIR": str(tmp_path / "b")}
    assert benchmark.corpus_root(tmp_path, override) == tmp_path / "b" / "codenarc"


def test_ensure_unpacks_then_leaves_an_up_to_date_corpus_alone(tmp_path: Path) -> None:
    target = tmp_path / "codenarc"
    first = benchmark.ensure(target)
    assert first is not None and first["action"] == "unpacked" and first["files"] > 500
    assert (target / "src" / "main" / "groovy" / "org" / "codenarc" / "CodeNarc.groovy").is_file()
    assert (target / "LICENSE.txt").is_file() and (target / "NOTICE.txt").is_file()
    settings = yaml.safe_load((target / ".groovyscope.yaml").read_text(encoding="utf-8"))
    assert settings["workspace"]["sourceRoots"] == ["src/main/groovy", "src/main/java"]
    assert "4.0.0" in (target / "README.md").read_text(encoding="utf-8")
    assert benchmark.status(target) == {"present": True, "version": "4.0.0"}
    second = benchmark.ensure(target)
    assert second is not None and second["action"] == "up-to-date"
    (target / "src" / "main" / "groovy" / "org" / "codenarc" / "CodeNarc.groovy").write_text("edited", encoding="utf-8")
    assert benchmark.ensure(target, refresh=True)["action"] == "refreshed"  # type: ignore[index]
    assert "edited" not in (target / "src" / "main" / "groovy" / "org" / "codenarc" / "CodeNarc.groovy").read_text()


def test_an_older_stamp_is_refreshed_and_a_broken_stamp_is_tolerated(tmp_path: Path) -> None:
    target = tmp_path / "codenarc"
    target.mkdir()
    (target / benchmark.STAMP).write_text(json.dumps({"version": "3.9.0"}), encoding="utf-8")
    result = benchmark.ensure(target)
    assert result is not None and result["action"] == "refreshed"
    (target / benchmark.STAMP).write_text("{not json", encoding="utf-8")
    assert benchmark.status(target) == {"present": True, "version": None}


def test_safe_members_rejects_paths_that_escape(tmp_path: Path) -> None:
    archive = tmp_path / "bad.zip"
    with zipfile.ZipFile(archive, "w") as zipped:
        zipped.writestr("../outside.txt", "x")
    with zipfile.ZipFile(archive) as zipped, pytest.raises(ValueError, match="escapes"):
        benchmark.safe_members(zipped, tmp_path / "target")


def test_missing_manifest_means_no_corpus(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.setattr(benchmark, "manifest", lambda: None)
    assert benchmark.ensure(tmp_path / "x") is None
