"""Change sets from git and the pure review rules."""

from __future__ import annotations

from collections.abc import Sequence
from pathlib import Path
from typing import Any

import pytest

from groovyscope import changes

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
        changes.changed_files(tmp_path, runner=broken, which=lambda _n: git)


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
