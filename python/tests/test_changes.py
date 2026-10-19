"""Change sets from git and the pure review rules."""

from __future__ import annotations

from collections.abc import Sequence
from pathlib import Path
from typing import Any

import click
import pytest

from groovyscope import changes, cli

DIFF = """diff --git a/src/A.groovy b/src/A.groovy
--- a/src/A.groovy
+++ b/src/A.groovy
@@ -3,0 +4,2 @@ class A {
+    int x
+    int y
@@ -10 +12 @@ class A {
-    old()
+    renamed()
@@ -20,2 +22,0 @@ class A {
-    gone()
-    gone2()
diff --git a/src/New.groovy b/src/New.groovy
--- /dev/null
+++ b/src/New.groovy
@@ -0,0 +1,3 @@
+class New {
+}
+
diff --git a/src/Gone.groovy b/src/Gone.groovy
--- a/src/Gone.groovy
+++ /dev/null
@@ -1,2 +0,0 @@
-class Gone {
-}
diff --git a/README.md b/README.md
--- a/README.md
+++ b/README.md
@@ -1 +1 @@
-a
+b
diff --git a/Jenkinsfile b/Jenkinsfile
--- a/Jenkinsfile
+++ b/Jenkinsfile
@@ -1 +1 @@
-pipeline {}
+pipeline { agent any }
"""


def test_parse_unified_diff_keeps_new_side_ranges_only() -> None:
    files = changes.parse_unified_diff(DIFF)
    assert [f["path"] for f in files] == [
        "src/A.groovy",
        "src/New.groovy",
        "src/Gone.groovy",
        "README.md",
        "Jenkinsfile",
    ]
    assert files[0] == {"path": "src/A.groovy", "status": "modified", "ranges": [[4, 5], [12, 12]]}
    assert files[1] == {"path": "src/New.groovy", "status": "added", "ranges": [[1, 3]]}
    assert files[2] == {"path": "src/Gone.groovy", "status": "deleted", "ranges": []}
    assert changes.reviewable("Jenkinsfile.prod") and not changes.reviewable("README.md")


def fake_runner(script: dict[str, tuple[int, str]]) -> changes.Runner:
    def run(command: Sequence[str], _cwd: Path) -> tuple[int, str]:
        key = " ".join(command[:3])
        for prefix, answer in script.items():
            if key.startswith(prefix):
                return answer
        return (1, f"unexpected {command}")

    return run


def test_changed_files_against_a_branch_and_the_index(tmp_path: Path) -> None:
    runner = fake_runner(
        {
            "git rev-parse --show-toplevel": (0, str(tmp_path)),
            "git merge-base main": (0, "abc123\n"),
            "git diff --unified=0": (0, DIFF),
        }
    )
    change = changes.changed_files(tmp_path, "main", runner=runner, which=lambda _n: "/usr/bin/git")
    assert change["mode"] == "branch" and change["base"] == "main"
    assert [f["path"] for f in change["files"]] == ["src/A.groovy", "src/New.groovy", "src/Gone.groovy", "Jenkinsfile"]
    staged = changes.changed_files(tmp_path, staged=True, runner=runner, which=lambda _n: "/usr/bin/git")
    assert staged["mode"] == "staged"
    assert changes.ranges_by_path(change) == {
        "src/A.groovy": [[4, 5], [12, 12]],
        "src/New.groovy": [[1, 3]],
        "Jenkinsfile": [[1, 1]],
    }


def test_changed_files_explains_every_failure(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="git is not installed"):
        changes.changed_files(tmp_path, which=lambda _n: None)
    git = "/usr/bin/git"
    not_repo = fake_runner({"git rev-parse --show-toplevel": (128, "fatal: not a git repository")})
    with pytest.raises(ValueError, match="not inside a git repository"):
        changes.changed_files(tmp_path, runner=not_repo, which=lambda _n: git)
    bad_base = fake_runner(
        {"git rev-parse --show-toplevel": (0, "/r"), "git merge-base nope": (128, "fatal: Not a valid object")}
    )
    with pytest.raises(ValueError, match="unknown base 'nope'"):
        changes.changed_files(tmp_path, "nope", runner=bad_base, which=lambda _n: git)
    broken = fake_runner(
        {
            "git rev-parse --show-toplevel": (0, "/r"),
            "git merge-base main": (0, "x"),
            "git diff --unified=0": (1, "boom"),
        }
    )
    with pytest.raises(ValueError, match="git diff failed: boom"):
        changes.changed_files(tmp_path, "main", runner=broken, which=lambda _n: git)
    nothing = fake_runner({"git rev-parse --show-toplevel": (0, "/r")})
    with pytest.raises(ValueError, match="no default branch found"):
        changes.changed_files(tmp_path, runner=nothing, which=lambda _n: git)


def test_the_default_base_is_the_repositorys_default_branch(tmp_path: Path) -> None:
    git = "/usr/bin/git"
    from_origin = fake_runner(
        {
            "git rev-parse --show-toplevel": (0, "/r"),
            "git symbolic-ref --quiet": (0, "origin/master\n"),
            "git merge-base origin/master": (0, "abc\n"),
            "git diff --unified=0": (0, DIFF),
        }
    )
    assert changes.changed_files(tmp_path, runner=from_origin, which=lambda _n: git)["base"] == "origin/master"

    def local_master(command: Sequence[str], _cwd: Path) -> tuple[int, str]:
        return (0, "") if list(command[-1:]) == ["master^{commit}"] else (1, "")

    assert changes.default_base(tmp_path, local_master) == "master"
    assert changes.default_base(tmp_path, lambda _c, _w: (1, "")) is None
    wrong = fake_runner(
        {
            "git rev-parse --show-toplevel": (0, "/r"),
            "git symbolic-ref --quiet": (0, "origin/master"),
            "git merge-base main": (128, "fatal: Not a valid object name main"),
        }
    )
    with pytest.raises(ValueError, match=r"unknown base 'main'.*default branch is origin/master"):
        changes.changed_files(tmp_path, "main", runner=wrong, which=lambda _n: git)


def test_default_runner_reports_missing_binaries(tmp_path: Path) -> None:
    code, output = changes.default_runner(["/nonexistent/git", "x"], tmp_path)
    assert code == -1 and "nonexistent" in output


def finding(path: str, line: int, severity: str, code: str) -> dict[str, Any]:
    return {"path": path, "line": line, "severity": severity, "code": code, "message": code}


def test_split_bucket_and_verdict() -> None:
    ranges = {"src/A.groovy": [[4, 5], [12, 12]]}
    rows = [
        finding("src/A.groovy", 4, "warning", "NoDef"),
        finding("src/A.groovy", 12, "error", "GS006"),
        finding("src/A.groovy", 5, "info", "GS008"),
        finding("src/A.groovy", 40, "warning", "LineLength"),
        finding("src/B.groovy", 1, "info", "LineLength"),
        finding("src/A.groovy", 4, "info", "TrailingWhitespace"),
    ]
    inside, elsewhere = changes.split_findings(rows, ranges)
    assert [f["line"] for f in inside] == [4, 12, 5, 4]
    assert [f["path"] for f in elsewhere] == ["src/A.groovy", "src/B.groovy"]
    buckets = changes.bucket(inside)
    assert [f["code"] for f in buckets["blocking"]] == ["GS006", "GS008"]  # security group blocks even as info
    assert [f["code"] for f in buckets["shouldFix"]] == ["NoDef"]
    assert [f["code"] for f in buckets["nits"]] == ["TrailingWhitespace"]
    assert changes.verdict(buckets, [], []).startswith("request changes: 2 blocking")
    assert changes.verdict({"blocking": [], "shouldFix": [1]}, [1, 2], [{"riskLevel": "high"}]) == (
        "approve with comments: 1 warning(s) to fix, 2 risky construct(s) added, "
        "1 changed declaration(s) with high impact"
    )
    assert changes.verdict({"blocking": [], "shouldFix": []}, [], []).startswith("approve:")


def test_touched_declarations_and_risky_sites() -> None:
    outline = [
        {"name": "A", "kind": "class", "lines": [1, 50]},
        {"name": "run", "kind": "method", "lines": [3, 8], "in": "A"},
        {"name": "other", "kind": "method", "lines": [20, 30], "in": "A"},
        {"name": "x", "kind": "variable", "lines": [4, 4], "in": "A"},
        {"name": "count", "kind": "field", "lines": [12, 12], "in": "A"},
    ]
    found = changes.touched_declarations(outline, [[4, 5], [12, 12]], limit=10)
    assert [d["symbol"] for d in found] == ["A", "A.run", "A.count"]
    assert changes.touched_declarations(outline, [[4, 5]], limit=1) == [
        {"symbol": "A", "kind": "class", "lines": [1, 50]}
    ]
    matches = [
        {"path": "src/A.groovy", "line": 4},
        {"path": "src/A.groovy", "line": 9},
        {"path": "src/C.groovy", "line": 4},
    ]
    assert changes.risky_in_change(matches, {"src/A.groovy": [[4, 5]]}) == [{"path": "src/A.groovy", "line": 4}]


def test_draft_comments_are_ordered_capped_and_friendly() -> None:
    buckets = {
        "blocking": [
            {
                "path": "src/A.groovy",
                "line": 4,
                "code": "GS003",
                "message": "Catch swallows the exception.",
                "fixable": True,
            }
        ],
        "shouldFix": [{"path": "src/A.groovy", "line": 9, "code": "NoDef", "message": "def on a public method"}],
        "nits": [{"path": "src/A.groovy", "line": 12, "code": "TrailingWhitespace", "message": "trailing whitespace"}],
    }
    risky = [{"kind": "catch", "path": "src/A.groovy", "line": 4, "meaning": "swallowed: the exception is dropped"}]
    uncovered = [{"path": "src/A.groovy", "line": 20, "name": "total", "missedLines": 3}]
    comments = changes.draft_comments(buckets, risky, uncovered, limit=10)
    assert [c["kind"] for c in comments] == ["blocking", "shouldFix", "risky", "uncovered", "nits"]
    first = comments[0]
    assert first["path"] == "src/A.groovy" and first["line"] == 4
    assert first["body"].startswith("This needs a change before we merge: Catch swallows the exception (`GS003`).")
    assert "costs nothing to accept" in first["body"] and "groovyscope explain GS003" in first["body"]
    assert comments[1]["body"].startswith("Worth fixing in this merge request")
    assert "deliberate" in comments[2]["body"] and "swallowed" in comments[2]["body"]
    assert "No test executes `total` yet (3 line(s) never executed)" in comments[3]["body"]
    assert comments[4]["body"].startswith("Nit, optional")
    assert "you" not in " ".join(c["body"].lower().split("happy")[0] for c in comments)
    assert len(changes.draft_comments(buckets, risky, uncovered, limit=2)) == 2
    assert changes.draft_comments({}, [], [], limit=0) == []


def test_summary_comment_matches_the_verdict() -> None:
    blocking = changes.summary_comment(
        "request changes: 1 blocking finding(s)", {"blocking": 1, "shouldFix": 2, "nits": 1}, [], 1, 4
    )
    assert blocking.startswith("Thanks for this merge request.")
    assert (
        "1 finding(s) to resolve before merging" in blocking
        and "Suggestions: 2 worth fixing here, 1 nit(s)." in blocking
    )
    assert "Tests: 1 changed method(s)" in blocking and blocking.endswith("(4 inline comment(s) posted.)")
    clean = changes.summary_comment("approve: nothing blocking", {"blocking": 0, "shouldFix": 0, "nits": 0}, [], 0, 0)
    assert "Nice work." in clean and "Suggestions" not in clean
    impact = [{"symbol": "app.A.total", "riskLevel": "high"}, {"symbol": "app.A.x", "riskLevel": "low"}]
    with_impact = changes.summary_comment(
        "approve with comments", {"blocking": 0, "shouldFix": 1, "nits": 0}, impact, 0, 1
    )
    assert "`app.A.total`" in with_impact and "Nothing blocks the merge." in with_impact


def test_triage_ranks_risks_smells_and_noise_with_the_fix_command() -> None:
    findings = [
        {"code": "GS002", "fixable": True},
        {"code": "GS002"},
        {"code": "CatchException"},
        {"code": "GS026"},
        {"code": "NoDef", "fixable": True},
        {"code": "LineLength"},
        {"code": "LineLength"},
    ]
    triage = changes.triage(findings, limit=5)
    assert triage["risks"]["count"] == 3 and triage["risks"]["codes"][0] == {
        "code": "GS002",
        "group": "truth",
        "count": 2,
        "fixable": 1,
    }
    assert [c["code"] for c in triage["smells"]["codes"]] == ["GS026"]
    assert triage["noise"]["count"] == 3 and triage["noise"]["codes"][0]["code"] == "LineLength"
    assert triage["fix"] == {"count": 2, "command": "groovyscope fix --code GS002 --code NoDef"}
    assert "fix" not in changes.triage([{"code": "LineLength"}], limit=5)
    # The suggested command parses with the real CLI (it once said `--codes`, which `fix` does not have).
    words = triage["fix"]["command"].split()
    assert words[:2] == ["groovyscope", "fix"]
    parsed = cli.main.get_command(click.Context(cli.main), "fix").make_context("fix", words[2:])  # type: ignore[union-attr]
    assert parsed.params["codes"] == ("GS002", "NoDef") and parsed.params["apply_changes"] is False
