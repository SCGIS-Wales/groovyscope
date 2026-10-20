"""scripts/bundle_benchmark.py refuses a corpus without its licence files (D-20)."""

from __future__ import annotations

import importlib.util
from pathlib import Path
from types import ModuleType

import pytest

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "bundle_benchmark.py"


def load_script() -> ModuleType:
    spec = importlib.util.spec_from_file_location("bundle_benchmark", SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.mark.skipif(not SCRIPT.is_file(), reason="repository checkout required")
def test_the_corpus_must_carry_its_licence_and_notice(tmp_path: Path) -> None:
    script = load_script()
    (tmp_path / "src" / "main").mkdir(parents=True)
    (tmp_path / "src" / "main" / "A.groovy").write_text("class A {}\n")
    with pytest.raises(ValueError, match=r"LICENSE\.txt"):
        script.corpus_files(tmp_path)
    (tmp_path / "LICENSE.txt").write_text("licence")
    with pytest.raises(ValueError, match=r"NOTICE\.txt"):
        script.corpus_files(tmp_path)
    (tmp_path / "NOTICE.txt").write_text("notice")
    assert [p.name for p in script.corpus_files(tmp_path)] == ["LICENSE.txt", "NOTICE.txt", "A.groovy"]
