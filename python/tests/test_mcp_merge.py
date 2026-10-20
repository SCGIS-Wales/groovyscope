"""The merge tools on real git repositories: status, hunks with suggestions, one write per file checked by the
parser, and the review of a merge for lost declarations and dropped changes. The engine's parser is a fake that
balances braces and finds `class` and `void` declarations."""

from __future__ import annotations

import re
import subprocess
from collections.abc import Callable, Mapping
from pathlib import Path
from typing import Any

import pytest

from groovyscope import conflicts, interaction, mcp_merge

FILE = "src/test/groovy/T.groovy"
BASE = "import org.junit.Test\n\nclass T {\n    @Test\n    void a() {\n        x()\n    }\n}\n"
DECLARATION = re.compile(r"^\s*(?:class (\w+)|void (\w+)\()")


@pytest.fixture(autouse=True)
def plain_git(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    """No global or system git configuration (a developer's merge.conflictStyle would change the hunks)."""
    monkeypatch.setenv("GIT_CONFIG_GLOBAL", str(tmp_path / "no-gitconfig"))
    monkeypatch.setenv("GIT_CONFIG_NOSYSTEM", "1")


def git(root: Path, *args: str) -> subprocess.CompletedProcess[str]:
    command = ["git", "-c", "user.email=t@example.com", "-c", "user.name=t", *args]
    return subprocess.run(command, cwd=root, capture_output=True, text=True, check=False)


def repository(tmp_path: Path, ours: str, theirs: str, base: str = BASE) -> Path:
    """A repository stopped in a merge of branch `feat` (``theirs``) into `main` (``ours``)."""
    root = tmp_path / "repo"
    (root / FILE).parent.mkdir(parents=True)
    git(root, "init", "-q", "-b", "main")
    (root / FILE).write_text(base, encoding="utf-8")
    git(root, "add", "-A")
    git(root, "commit", "-qm", "base")
    git(root, "checkout", "-qb", "feat")
    (root / FILE).write_text(theirs, encoding="utf-8")
    git(root, "commit", "-qam", "feat")
    git(root, "checkout", "-q", "main")
    (root / FILE).write_text(ours, encoding="utf-8")
    git(root, "commit", "-qam", "main")
    git(root, "merge", "feat")
    return root


def with_test(name: str, body: str = "        y()\n") -> str:
    return BASE.replace("    }\n}\n", f"    }}\n\n    @Test\n    void {name}() {{\n{body}    }}\n}}\n")


def fake_parse(text: str) -> dict[str, Any]:
    ok = not conflicts.has_markers(text) and "=======" not in text and _balanced(text)
    declarations: list[dict[str, Any]] = []
    lines = text.splitlines()
    owner = ""
    for index, line in enumerate(lines):
        found = DECLARATION.match(line)
        if not found:
            continue
        end = _block_end(lines, index)
        if found.group(1):
            owner = found.group(1)
            declarations.append({"name": owner, "kind": "class", "line": index + 1, "endLine": end})
        else:
            declarations.append({"name": f"{owner}.{found.group(2)}", "kind": "method", "line": index + 1, "endLine": end})
    errors = [] if ok else [{"line": 1, "column": 1, "message": "unexpected input"}]
    return {"ok": ok, "errors": errors, "declarations": declarations}


def _balanced(text: str) -> bool:
    depth = 0
    for char in text:
        depth += {"{": 1, "}": -1}.get(char, 0)
        if depth < 0:
            return False
    return depth == 0


def _block_end(lines: list[str], start: int) -> int:
    depth = 0
    for index in range(start, len(lines)):
        depth += lines[index].count("{") - lines[index].count("}")
        if depth == 0 and "{" in "".join(lines[start : index + 1]):
            return index + 1
    return len(lines)


def engine(calls: list[Mapping[str, Any]]) -> Callable[[str, dict[str, Any]], Any]:
    def call(method: str, params: dict[str, Any]) -> Any:
        assert method == "groovyscope/parse"
        assert len(params["texts"]) <= mcp_merge.PARSE_BATCH
        calls.append(params)
        return {"results": [fake_parse(text) for text in params["texts"]]}

    return call


def tools(root: Path, calls: list[Mapping[str, Any]] | None = None) -> dict[str, Callable[..., Any]]:
    return mcp_merge.merge_tools(root, engine(calls if calls is not None else []), 20, {}, confirm_policy="never")


def test_status_names_the_merge_its_unmerged_files_and_their_hunks(tmp_path: Path) -> None:
    root = repository(tmp_path, with_test("ca"), with_test("venafi"))
    status = tools(root)["merge_conflicts"]()
    assert status["operation"] == "merge" and status["incoming"] == "Merge branch 'feat'"
    assert status["unmerged"] == [{"path": FILE, "hunks": 1, "suggested": 1}]
    assert "resolve_conflicts" in status["next"]


def test_status_outside_a_merge_says_so_and_finds_markers_in_staged_files(tmp_path: Path) -> None:
    root = repository(tmp_path, with_test("ca"), with_test("venafi"))
    git(root, "add", FILE)  # staged with its markers: git no longer lists it as unmerged
    status = tools(root)["merge_conflicts"]()
    assert status["unmerged"] == []
    assert status["markersInChangedFiles"][0]["path"] == FILE and "warning" in status
    git(root, "merge", "--abort")
    assert "no merge" in tools(root)["merge_conflicts"]()["note"]


def test_a_file_view_shows_numbered_sides_the_base_from_the_index_and_the_join(tmp_path: Path) -> None:
    root = repository(tmp_path, with_test("ca"), with_test("venafi"))
    view = tools(root)["merge_conflicts"](FILE)
    (hunk,) = view["detail"]
    assert hunk["suggestion"]["take"] == "both" and hunk["size"]["base"] == 0
    assert hunk["baseLines"] == ["(empty: both sides added these lines)"]
    assert hunk["oursLabel" if False else "ours"] == "HEAD" and hunk["theirs"] == "feat"
    text = (root / FILE).read_text(encoding="utf-8").splitlines()
    for row in hunk["oursLines"] + hunk["theirsLines"]:
        number, _, content = row.partition("| ")
        assert text[int(number) - 1] == content  # numbers are the file's own lines
    assert "keepingBoth" in hunk and "tip" not in view
    assert tools(root)["merge_conflicts"](FILE, hunk=2)["error"].endswith("it has 1")
    assert "outside the workspace" in tools(root)["merge_conflicts"]("../x")["error"]


def test_resolving_both_added_tests_keeps_both_with_their_braces_in_one_write(tmp_path: Path) -> None:
    root = repository(tmp_path, with_test("ca"), with_test("venafi"))
    calls: list[Mapping[str, Any]] = []
    dry = tools(root, calls)["resolve_conflicts"](FILE, [{"hunk": 1, "take": "both"}])
    assert dry["parses"] is True and dry["written"] is False and "dry run" in dry["note"]
    assert calls and calls[0]["name"] == "T.groovy"
    done = tools(root)["resolve_conflicts"](FILE, [{"hunk": 1, "take": "both"}], apply=True)
    assert done["written"] is True and done["remaining"] == [] and "git add" in done["next"]
    result = (root / FILE).read_text(encoding="utf-8")
    assert not conflicts.has_markers(result) and result.count("@Test") == 3 and _balanced(result)
    assert "void ca()" in result and "void venafi()" in result


def test_a_result_that_does_not_parse_is_not_written_unless_forced(tmp_path: Path) -> None:
    root = repository(tmp_path, with_test("ca"), with_test("venafi"))
    before = (root / FILE).read_text(encoding="utf-8")
    text = [{"hunk": 1, "text": "    @Test\n    void broken() {\n"}]
    refused = tools(root)["resolve_conflicts"](FILE, text, apply=True)
    assert refused["parses"] is False and refused["written"] is False and refused["errors"]
    assert (root / FILE).read_text(encoding="utf-8") == before
    forced = tools(root)["resolve_conflicts"](FILE, text, apply=True, force=True)
    assert forced["written"] is True


def test_resolution_requests_are_checked(tmp_path: Path) -> None:
    root = repository(tmp_path, with_test("ca"), with_test("venafi"))
    resolve = tools(root)["resolve_conflicts"]
    assert "name at least one hunk" in resolve(FILE)["error"]
    assert "no hunk 3" in resolve(FILE, [{"hunk": 3, "take": "ours"}])["error"]
    assert "not both" in resolve(FILE, [{"hunk": 1, "take": "ours", "text": "x"}])["error"]
    assert "`take` is one of" in resolve(FILE, [{"hunk": 1, "take": "mine"}])["error"]
    (root / FILE).write_text((root / FILE).read_text().replace("<<<<<<< HEAD\n", ""), encoding="utf-8")
    leftover = resolve(FILE, [{"hunk": 1, "take": "ours"}])
    assert "outside complete hunks" in leftover["error"] and leftover["leftoverMarkers"]


def test_suggested_takes_one_sided_hunks_and_a_non_groovy_file_is_not_parsed(tmp_path: Path) -> None:
    base = "a\nb\nc\n"
    root = repository(tmp_path, "a\nB\nc\n", "a\nb2\nc\n", base=base)
    calls: list[Mapping[str, Any]] = []
    answer = tools(root, calls)["resolve_conflicts"](FILE, suggested=True)
    assert "name at least one hunk" in answer["error"]  # both changed b: no suggestion
    readme = root / "README.md"
    readme.write_text("<<<<<<< HEAD\nsame\n=======\nsame\n>>>>>>> feat\n", encoding="utf-8")
    done = tools(root, calls)["resolve_conflicts"]("README.md", suggested=True, apply=True)
    assert done["written"] is True and done["parses"].startswith("not checked")
    assert readme.read_text(encoding="utf-8") == "same\n" and calls == []


def test_an_engine_that_cannot_parse_leaves_the_check_open(tmp_path: Path) -> None:
    root = repository(tmp_path, with_test("ca"), with_test("venafi"))

    def down(method: str, params: dict[str, Any]) -> Any:
        raise RuntimeError("Unsupported request method: groovyscope/parse")

    resolve = mcp_merge.merge_tools(root, down, 20, {}, confirm_policy="never")["resolve_conflicts"]
    answer = resolve(FILE, [{"hunk": 1, "take": "ours"}], apply=True)
    assert answer["parses"].startswith("not checked") and answer["written"] is True


def test_a_declined_confirmation_writes_nothing(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    root = repository(tmp_path, with_test("ca"), with_test("venafi"))
    monkeypatch.setattr(interaction, "confirm", lambda *_a, **_k: interaction.DECLINED)
    answer = tools(root)["resolve_conflicts"](FILE, [{"hunk": 1, "take": "ours"}], apply=True)
    assert answer["written"] is False and answer["confirmation"] == interaction.DECLINED


def test_review_finds_a_test_the_resolution_lost(tmp_path: Path) -> None:
    root = repository(tmp_path, with_test("ca"), with_test("venafi"))
    tools(root)["resolve_conflicts"](FILE, [{"hunk": 1, "take": "ours"}], apply=True)
    review = tools(root)["merge_review"]()
    assert review["operation"] == "merge" and review["base"] is True and review["files"] == 1
    (flagged,) = review["flagged"]
    assert {"symbol": "T.venafi", "finding": "lost"}.items() <= flagged["findings"][0].items()
    assert flagged["members"] == {"ours": 3, "theirs": 3, "base": 2, "result": 3}


def test_review_of_a_good_resolution_is_clean_and_works_after_the_commit(tmp_path: Path) -> None:
    root = repository(tmp_path, with_test("ca"), with_test("venafi"))
    tools(root)["resolve_conflicts"](FILE, [{"hunk": 1, "take": "both"}], apply=True)
    assert tools(root)["merge_review"]()["flagged"] == []
    git(root, "commit", "-qam", "merge")
    after = tools(root)["merge_review"]()
    assert after["operation"] == "merge commit" and after["flagged"] == [] and after["files"] == 1


def test_review_reports_markers_unparsable_results_and_no_merge(tmp_path: Path) -> None:
    root = repository(tmp_path, with_test("ca"), with_test("venafi"))
    assert "conflict markers" in tools(root)["merge_review"]()["flagged"][0]["error"]
    (root / FILE).write_text("class T {\n", encoding="utf-8")
    assert tools(root)["merge_review"]()["flagged"][0]["parses"] is False
    git(root, "merge", "--abort")
    assert "not a merge commit" in tools(root)["merge_review"]()["error"]


@pytest.mark.parametrize(
    ("ours", "theirs", "base", "result", "finding"),
    [
        ("o", "b", "b", "b", "change dropped"),
        ("b", "t", "b", "t", None),
        ("o", "t", "b", "o", "one side kept"),
        ("o", "t", "b", "m", "blended"),
        ("s", "s", "b", "m", "changed in the merge"),
        ("o", "t", None, "o", "one side kept"),
    ],
)
def test_member_bodies_are_compared_against_the_base(
    ours: str, theirs: str, base: str | None, result: str, finding: str | None
) -> None:
    def members(body: str | None) -> mcp_merge.Members:
        return {} if body is None else {"T.m": ("method", body, 3)}

    rows = mcp_merge.compare(members(ours), members(theirs), members(base) if base else None, members(result))
    assert [row["finding"] for row in rows] == ([finding] if finding else [])


def test_deletions_are_told_from_losses_with_a_base() -> None:
    kept = {"T.m": ("method", "b", 3)}
    assert mcp_merge.compare({}, kept, kept, {}) == []  # ours deleted it, the result carries that
    rows = mcp_merge.compare({}, kept, kept, kept)
    assert rows[0]["finding"] == "deletion undone"
    changed = {"T.m": ("method", "t", 3)}
    assert mcp_merge.compare({}, changed, kept, changed)[0]["finding"] == "kept after deletion"
    assert mcp_merge.compare(kept, {}, None, {})[0]["detail"].endswith("(no base)")


def test_hand_merged_files_and_settings_are_read(tmp_path: Path) -> None:
    limits = mcp_merge.merge_settings({"merge": {"sideLines": 8, "maxFileBytes": 10}})
    assert limits["sideLines"] == 8 and limits["contextLines"] == 3 and limits["maxFileBytes"] == 10
    big = tmp_path / "big.groovy"
    big.write_text("x" * 20, encoding="utf-8")
    assert mcp_merge.read_text(big, 10) is None and mcp_merge.read_text(big, 0) == "x" * 20
    binary = tmp_path / "b.bin"
    binary.write_bytes(b"a\0b")
    assert mcp_merge.read_text(binary, 0) is None
    latin = tmp_path / "l.txt"
    latin.write_bytes("caf\xe9".encode("latin-1"))
    assert mcp_merge.read_text(latin, 0) is None
