"""The calibration script of the edit gate: comment and Javadoc lines per method, test classes against subjects."""

from __future__ import annotations

import importlib.util
from collections.abc import Sequence
from pathlib import Path
from typing import Any

import pytest

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "edit_gate_metrics.py"
pytestmark = pytest.mark.skipif(not SCRIPT.is_file(), reason="repository checkout required")

SOURCE = """package demo

/**
 * Adds things.
 * Twice over.
 */
@Deprecated
int add(int a, int b) {
    // add them
    // and return
    // the sum
    String s = "// not a comment"
    return a + b // trailing
}
"""


def load_script() -> Any:
    spec = importlib.util.spec_from_file_location("edit_gate_metrics", SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_line_kinds_tell_comments_code_strings_and_javadoc_apart() -> None:
    kinds = load_script().line_kinds(SOURCE + "def t = '''a\n// b'''\n/* x */ int y = 1\n")
    assert {9, 10, 11} <= kinds["comment"] and 12 in kinds["code"] and 13 in kinds["code"]
    assert 12 not in kinds["comment"] and 13 not in kinds["comment"]  # the slashes are in a string; trailing is code
    assert kinds["javadoc"] == [(3, 6)]
    assert 16 in kinds["code"] and 16 not in kinds["comment"]  # a line ending a triple-quoted string
    assert 17 in kinds["code"]  # block comment before code on the same line


def test_method_rows_count_body_comments_runs_and_javadoc_text() -> None:
    script = load_script()
    rows = script.method_rows("A.groovy", SOURCE, [{"name": "add", "kind": "method", "line": 7, "endLine": 14}])
    assert rows == [{"path": "A.groovy", "name": "add", "code": 5, "comments": 3, "longestRun": 3, "javadoc": 2}]
    assert script.method_rows("A.groovy", SOURCE, [{"name": "A", "kind": "class", "line": 1, "endLine": 14}]) == []


def test_measure_summarises_methods_and_tests_against_their_subjects(tmp_path: Path) -> None:
    script = load_script()
    (tmp_path / "Foo.groovy").write_text("class Foo {\n  int a() { 1 }\n}\n", encoding="utf-8")
    (tmp_path / "FooSpec.groovy").write_text("class FooSpec {\n" + "  def x() { 1 }\n" * 5 + "}\n", encoding="utf-8")

    def parse(_name: str, texts: Sequence[str]) -> list[dict[str, Any]]:
        out = []
        for text in texts:
            lines = text.splitlines()
            out.append(
                {
                    "declarations": [
                        {"name": f"m{n}", "kind": "method", "line": n + 1, "endLine": n + 1}
                        for n, line in enumerate(lines)
                        if "()" in line
                    ]
                }
            )
        return out

    numbers = script.measure(tmp_path, parse)
    assert numbers["files"] == 2 and numbers["methods"] == 6 and numbers["testClasses"] == 1
    assert numbers["testToCodeRatio"]["max"] == pytest.approx(7 / 3) and numbers["testMethodsPerClass"]["max"] == 5
    assert "| commentLinesPerMethod | 6 |" in script.markdown(numbers)
    assert script.summary([]) == {"count": 0, "median": 0.0, "p90": 0.0, "max": 0.0}
    assert script.subject_of(Path("Foo.groovy")) is None and script.subject_of(Path("FooIT.groovy")) == "Foo"
