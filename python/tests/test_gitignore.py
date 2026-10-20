"""The workspace's .groovyscope/ folder (classpath export, contract baseline, workspace profiles) is kept out of git."""

from __future__ import annotations

import subprocess
from collections.abc import Sequence
from pathlib import Path

import pytest

from groovyscope import gitignore


def repo(tmp_path: Path) -> Path:
    (tmp_path / ".git").mkdir(parents=True)
    return tmp_path


def not_ignored(command: Sequence[str], **_: object) -> subprocess.CompletedProcess[str]:
    return subprocess.CompletedProcess(list(command), 1, "", "")


def ignored(command: Sequence[str], **_: object) -> subprocess.CompletedProcess[str]:
    return subprocess.CompletedProcess(list(command), 0, ".groovyscope/x\n", "")


def test_outside_a_git_repository_nothing_is_written(tmp_path: Path) -> None:
    assert gitignore.ensure(tmp_path, write=True, run=not_ignored)["state"] == "no-repository"
    assert not (tmp_path / ".gitignore").exists()


def test_the_entry_is_added_once_to_a_new_or_existing_file(tmp_path: Path) -> None:
    root = repo(tmp_path)
    first = gitignore.ensure(root, write=True, run=not_ignored)
    assert first["state"] == "added" and first["path"] == str(root / ".gitignore")
    text = (root / ".gitignore").read_text(encoding="utf-8")
    assert text.splitlines()[-1] == ".groovyscope/" and text.endswith("\n")
    assert gitignore.ensure(root, write=True, run=not_ignored)["state"] == "present"
    assert (root / ".gitignore").read_text(encoding="utf-8") == text

    other = repo(tmp_path / "other")
    (other / ".gitignore").write_text("build/\n*.log", encoding="utf-8")  # no final newline
    assert gitignore.ensure(other, write=True, run=not_ignored)["state"] == "added"
    lines = (other / ".gitignore").read_text(encoding="utf-8").splitlines()
    assert lines[:2] == ["build/", "*.log"] and lines[-1] == ".groovyscope/"
    assert lines.count(".groovyscope/") == 1


@pytest.mark.parametrize(
    "line", [".groovyscope", ".groovyscope/", "/.groovyscope", "/.groovyscope/", "**/.groovyscope/", ".groovyscope/**"]
)
def test_an_equivalent_line_counts_as_present(tmp_path: Path, line: str) -> None:
    root = repo(tmp_path)
    (root / ".gitignore").write_text(f"# local\n  {line}  \n", encoding="utf-8")
    assert gitignore.ensure(root, write=True, run=not_ignored)["state"] == "present"
    assert (root / ".gitignore").read_text(encoding="utf-8") == f"# local\n  {line}  \n"


def test_a_rule_elsewhere_that_already_ignores_it_is_enough(tmp_path: Path) -> None:
    root = repo(tmp_path)
    (root / ".gitignore").write_text(".groovy*\n", encoding="utf-8")
    assert gitignore.ensure(root, write=True, run=ignored)["state"] == "present"
    assert (root / ".gitignore").read_text(encoding="utf-8") == ".groovy*\n"


def test_a_deliberate_negation_is_respected(tmp_path: Path) -> None:
    root = repo(tmp_path)
    (root / ".gitignore").write_text("!.groovyscope/\n", encoding="utf-8")
    assert gitignore.ensure(root, write=True, run=not_ignored)["state"] == "negated"
    assert (root / ".gitignore").read_text(encoding="utf-8") == "!.groovyscope/\n"


def test_a_workspace_inside_a_repository_uses_the_repository_root(tmp_path: Path) -> None:
    root = repo(tmp_path)
    workspace = root / "services" / "billing"
    workspace.mkdir(parents=True)
    assert gitignore.ensure(workspace, write=True, run=not_ignored)["path"] == str(root / ".gitignore")
    assert not (workspace / ".gitignore").exists()


def test_without_write_it_only_reports_and_git_failures_fall_back_to_the_file(tmp_path: Path) -> None:
    root = repo(tmp_path)
    assert gitignore.ensure(root, write=False, run=not_ignored)["state"] == "missing"
    assert not (root / ".gitignore").exists()

    def broken(command: Sequence[str], **_: object) -> subprocess.CompletedProcess[str]:
        raise OSError("git not installed")

    assert gitignore.ensure(root, write=True, run=broken)["state"] == "added"


def test_the_entry_is_appended_without_touching_the_lines_already_there(tmp_path: Path) -> None:
    root = repo(tmp_path)
    team = b"# caf\xe9 build output\r\nbuild/\r\n*.class\r\n"  # CRLF endings and a Latin-1 comment
    (root / ".gitignore").write_bytes(team)
    assert gitignore.ensure(root, write=True, run=not_ignored)["state"] == "added"
    written = (root / ".gitignore").read_bytes()
    assert written.startswith(team)
    assert written[len(team) :] == f"\r\n{gitignore.COMMENT}\r\n.groovyscope/\r\n".encode()
    assert gitignore.ensure(root, write=True, run=not_ignored)["state"] == "present"


def test_a_symlinked_gitignore_stays_a_link_and_its_target_gets_the_entry(tmp_path: Path) -> None:
    root = repo(tmp_path / "repo")
    shared = tmp_path / "shared.gitignore"
    shared.write_text("build/\n", encoding="utf-8")
    (root / ".gitignore").symlink_to(shared)
    assert gitignore.ensure(root, write=True, run=not_ignored)["state"] == "added"
    assert (root / ".gitignore").is_symlink()
    assert shared.read_text(encoding="utf-8").splitlines()[-1] == ".groovyscope/"
