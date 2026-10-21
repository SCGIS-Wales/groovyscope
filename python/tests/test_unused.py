"""Unused code leads every answer: the `unused` block and `attention` line, the group first in a review, a page
that shows every code, `find_unused`, the hook line, the review verdict and `groovyscope unused`."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from click.testing import CliRunner

from groovyscope import changes, cli, hooks, practices, unused
from groovyscope.cli import common as cli_common

URI = "file:///ws/src/A.groovy"


def item(line: int, severity: int, code: str, message: str = "m") -> dict[str, Any]:
    return {
        "range": {"start": {"line": line, "character": 0}},
        "severity": severity,
        "source": "codenarc",
        "code": code,
        "message": message,
    }


def noisy_file() -> dict[str, Any]:
    """Many style warnings of one code, and unused code of lower or equal severity after them."""
    style = [item(n, 2, "LineLength") for n in range(40)]
    dead = [
        item(50, 3, "UnusedImport", "The [java.util.Base64] import is never referenced"),
        item(60, 2, "UnusedPrivateMethodParameter", "Parameter [canonicalName] is never referenced"),
        item(70, 2, "UnusedVariable", "The variable [token] is not used"),
    ]
    return {"diagnostics": {URI: [*style, *dead, item(5, 2, "Indentation")]}, "summary": {}}


def test_summary_counts_by_kind_and_marks_the_safe_fixes() -> None:
    rows = [
        {"path": "b", "line": 9, "code": "UnusedVariable", "message": "v"},
        {"path": "a", "line": 2, "code": "UnusedImport", "message": "i"},
        {"path": "a", "line": 1, "code": "LineLength", "message": "l"},
        {"path": "a", "line": 3, "code": "UnusedMethodParameter", "message": "p"},
    ]
    found = unused.summary(rows, limit=2)
    assert found["total"] == 3 and found["byKind"] == {"imports": 1, "variables": 1, "parameters": 1}
    assert [i["code"] for i in found["items"]] == ["UnusedImport", "UnusedMethodParameter"]  # by path and line
    assert found["items"][0]["fixable"] is True and "fixable" not in found["items"][1]
    assert unused.counted({"imports": 1, "variables": 5}) == "1 import, 5 variables"
    line = unused.attention(found)
    assert line is not None and line.startswith("unused code: 1 import, 1 variable, 1 parameter")
    assert "removes the imports safely" in line and "hand edit" in line
    imports_only = unused.summary(rows[1:2], limit=5)
    assert "hand edit" not in str(unused.attention(imports_only))
    assert unused.attention(unused.summary([], limit=5)) is None
    assert unused.with_unused({}, [], limit=5) == {"unused": {"total": 0, "byKind": {}, "items": []}}


def test_a_page_shows_one_of_each_code_before_the_second_of_any() -> None:
    rows = [{"path": "a", "line": n, "code": "LineLength", "severity": "warning"} for n in range(5)]
    rows += [{"path": "z", "line": 1, "code": "UnusedVariable", "severity": "warning"}]
    rows += [{"path": "a", "line": 9, "code": "GS001", "severity": "error"}]
    ordered = unused.diverse(rows, {"error": 0, "warning": 1})
    assert [r["code"] for r in ordered[:3]] == ["GS001", "LineLength", "UnusedVariable"]


def test_check_puts_unused_code_first_whatever_the_style_noise(tools: dict[str, Any], engine: dict[str, Any]) -> None:
    engine["responses"]["groovyscope/check"] = noisy_file()
    answer = tools["check"](paths=["src/A.groovy"])
    assert answer["unused"]["total"] == 3
    assert answer["unused"]["byKind"] == {"imports": 1, "variables": 1, "parameters": 1}
    assert {i["line"] for i in answer["unused"]["items"]} == {51, 61, 71}
    assert answer["attention"].startswith("unused code: 1 import, 1 variable, 1 parameter")
    assert sorted(json.loads(json.dumps(answer)))[0] == "attention"  # compact JSON sorts keys: it is read first
    assert [f["code"] for f in answer["items"]] == ["LineLength", "Indentation", "UnusedPrivateMethodParameter"]
    clean = {"diagnostics": {URI: [item(1, 2, "LineLength")]}, "summary": {}}
    engine["responses"]["groovyscope/check"] = clean
    quiet = tools["check"]()
    assert quiet["unused"]["total"] == 0 and "attention" not in quiet


def test_review_lists_the_unused_group_first_and_in_full(tools: dict[str, Any], engine: dict[str, Any]) -> None:
    engine["responses"]["groovyscope/check"] = noisy_file()
    reviewed = tools["review"](paths=["src/A.groovy"], top=1)
    first = reviewed["groups"][0]
    assert first["group"] == "unused" and first["total"] == 3 and len(first["findings"]) == 3
    assert reviewed["attention"].startswith("unused code") and reviewed["unused"]["total"] == 3
    assert practices.group_of("UnusedImport") == "unused" and practices.group_of("LineLength") == "style"


def test_review_changes_names_unused_code_in_the_verdict_and_never_as_a_nit(
    tools: dict[str, Any], engine: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    engine["responses"]["groovyscope/check"] = {
        "diagnostics": {URI: [item(4, 3, "UnusedImport"), item(30, 2, "UnusedVariable")]},
        "summary": {},
    }
    change = {
        "base": "main",
        "mode": "branch",
        "files": [{"path": "src/A.groovy", "status": "modified", "ranges": [[5, 5]]}],
    }
    monkeypatch.setattr(changes, "changed_files", lambda *_a, **_k: change)
    answer = tools["review_changes"]()
    assert answer["unused"]["byKind"] == {"imports": 1} and answer["unused"]["elsewhereInChangedFiles"] == {
        "variables": 1
    }
    assert answer["verdict"] == "approve with comments: unused 1 import to remove"
    assert answer["attention"].startswith("unused code on the changed lines: 1 import")
    assert answer["counts"]["shouldFix"] == 1 and answer["counts"]["nits"] == 0
    assert "Unused code on the changed lines: 1 import" in answer["summaryComment"]


def test_verdict_and_summary_carry_unused_code_beside_blocking_findings() -> None:
    blocking = changes.verdict({"blocking": [1], "shouldFix": []}, [], [], {"parameters": 2})
    assert blocking == "request changes: 1 blocking finding(s) on the changed lines, and unused 2 parameters to remove"
    assert changes.verdict({"blocking": [], "shouldFix": []}, [], [], {}).startswith("approve:")
    comment = changes.summary_comment("approve", {"blocking": 0}, [], 0, 0, unused_kinds={"variables": 1})
    assert "Unused code on the changed lines: 1 variable" in comment


def test_find_unused_lists_only_unused_code_for_paths_or_changed_lines(
    tools: dict[str, Any], engine: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    engine["responses"]["groovyscope/check"] = noisy_file()
    answer = tools["find_unused"](paths=["src/A.groovy"])
    assert answer["unused"]["total"] == 3 and "attention" in answer
    assert engine["calls"][-1][1]["include"] == ["lint"]
    assert "note" not in answer  # all three fit the cap of the fixture (maxResults 3)
    change = {
        "base": "main",
        "mode": "branch",
        "files": [{"path": "src/A.groovy", "status": "modified", "ranges": [[71, 71]]}],
    }
    monkeypatch.setattr(changes, "changed_files", lambda *_a, **_k: change)
    changed = tools["find_unused"](changed_only=True)
    assert changed["unused"]["byKind"] == {"variables": 1}
    monkeypatch.setattr(changes, "changed_files", lambda *_a, **_k: {"base": "main", "mode": "branch", "files": []})
    assert tools["find_unused"](changed_only=True)["note"] == "no Groovy changes against main"

    def no_git(*_a: Any, **_k: Any) -> Any:
        raise ValueError("not a git repository")

    monkeypatch.setattr(changes, "changed_files", no_git)
    assert tools["find_unused"](changed_only=True) == {"error": "not a git repository"}


def test_the_hook_prints_an_unused_line_after_the_header(tmp_path: Path) -> None:
    rows = [
        {
            "path": "A.groovy",
            "line": 3,
            "column": 1,
            "level": 2,
            "severity": "warning",
            "source": "codenarc",
            "code": "UnusedImport",
            "message": "unused",
        },
        {
            "path": "A.groovy",
            "line": 9,
            "column": 1,
            "level": 2,
            "severity": "warning",
            "source": "codenarc",
            "code": "UnusedVariable",
            "message": "unused",
        },
    ]
    lines = hooks.render_after_write(rows, [tmp_path / "A.groovy"], tmp_path, 12).splitlines()
    assert lines[1] == "  UNUSED: 1 import, 1 variable (lines 3 import, 9 variable); remove them now"


def test_the_unused_command_exits_one_on_unused_code(
    runner: CliRunner, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    answers = iter(
        [
            {
                "unused": {
                    "total": 1,
                    "byKind": {"imports": 1},
                    "items": [
                        {
                            "path": "A.groovy",
                            "line": 3,
                            "kind": "imports",
                            "code": "UnusedImport",
                            "message": "m",
                            "fixable": True,
                        }
                    ],
                }
            },
            {"unused": {"total": 0, "byKind": {}, "items": []}},
            {"error": "not a git repository"},
        ]
    )
    monkeypatch.setattr(cli_common, "daemon_socket_for", lambda _obj: (tmp_path / "s", {}))
    monkeypatch.setattr(cli_common, "workspace_tools", lambda *_a: {"find_unused": lambda *_x, **_k: next(answers)})
    found = runner.invoke(cli.main, ["--root", str(tmp_path), "unused"])
    assert found.exit_code == 1 and "A.groovy:3: unused import [UnusedImport] m (fix removes it)" in found.output
    assert "1 unused: 1 import" in found.output
    clean = runner.invoke(cli.main, ["--root", str(tmp_path), "unused", "--json"])
    assert clean.exit_code == 0 and json.loads(clean.output)["unused"]["total"] == 0
    failed = runner.invoke(cli.main, ["--root", str(tmp_path), "unused", "--changed"])
    assert failed.exit_code == 1 and "not a git repository" in failed.output


def test_find_unused_says_when_more_than_the_cap_exist(tools: dict[str, Any], engine: dict[str, Any]) -> None:
    rows = [item(n, 2, "UnusedVariable") for n in range(5)]
    engine["responses"]["groovyscope/check"] = {"diagnostics": {URI: rows}, "summary": {}}
    answer = tools["find_unused"]()
    assert answer["unused"]["total"] == 5 and len(answer["unused"]["items"]) == 3
    assert answer["note"] == "3 of 5 listed; narrow the paths to see the rest"


def test_one_import_reported_by_two_rules_is_one_item_with_its_name() -> None:
    rows = [
        {
            "path": "A.groovy",
            "line": 2,
            "code": "UnnecessaryGroovyImport",
            "message": "A Groovy file does not need it.",
        },
        {
            "path": "A.groovy",
            "line": 2,
            "code": "UnusedImport",
            "message": "Imports. The [java.util.List] import is never referenced",
        },
        {
            "path": "A.groovy",
            "line": 9,
            "code": "UnusedVariable",
            "message": "Checks. The variable [token] in class A is not used",
        },
    ]
    found = unused.summary(rows, limit=5)
    assert found["total"] == 2 and found["byKind"] == {"imports": 1, "variables": 1}
    first, second = found["items"]
    assert first == {
        "path": "A.groovy",
        "line": 2,
        "kind": "imports",
        "code": "UnusedImport",
        "name": "java.util.List",
        "message": "The [java.util.List] import is never referenced",
        "fixable": True,
    }
    assert second["name"] == "token" and second["message"] == "The variable [token] in class A is not used"
    assert unused.brief("", "UnnecessaryGroovyImport") == {"message": "Groovy imports this by default"}
    assert unused.brief("No brackets here. Second sentence.") == {"message": "No brackets here."}
