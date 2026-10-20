"""``groovyscope merge``: status, show, resolve and review from a terminal, on a real merge."""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

import pytest
from click.testing import CliRunner

from groovyscope import cli
from groovyscope.cli import common as cli_common
from groovyscope.config import load_config
from tests.test_mcp_merge import FILE, fake_parse, git, plain_git, repository, with_test

__all__ = ["plain_git"]  # the fixture, used by every test here


@pytest.fixture
def merge_root(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    root = repository(tmp_path, with_test("ca"), with_test("venafi"))

    def socket_for(obj: dict[str, Any]) -> tuple[Path, dict[str, Any]]:
        return (tmp_path / "socket", load_config(obj["root"], obj["config_path"], dict(os.environ)))

    def rpc(_socket: Path, method: str, params: dict[str, Any], _timeout: float) -> dict[str, Any]:
        assert method == "groovyscope/parse"
        return {"results": [fake_parse(text) for text in params["texts"]]}

    monkeypatch.setattr(cli_common, "daemon_socket_for", socket_for)
    monkeypatch.setattr(cli_common, "_RPC", rpc)
    return root


def test_status_and_show_need_no_engine(runner: CliRunner, merge_root: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(cli_common, "daemon_socket_for", lambda _obj: pytest.fail("status starts no engine"))
    status = runner.invoke(cli.main, ["--root", str(merge_root), "merge", "status"])
    assert status.exit_code == 0, status.output
    assert "merge in progress: Merge branch 'feat'" in status.output
    assert f"unmerged {FILE}: 1 hunk(s), 1 with a suggestion" in status.output
    as_json = runner.invoke(cli.main, ["--root", str(merge_root), "merge", "status", "--json"])
    assert json.loads(as_json.output)["operation"] == "merge"
    show = runner.invoke(cli.main, ["--root", str(merge_root), "merge", "show", FILE])
    assert show.exit_code == 0, show.output
    assert "hunk 1 (lines" in show.output and "suggestion: both" in show.output and "  theirs:" in show.output
    assert json.loads(runner.invoke(cli.main, ["--root", str(merge_root), "merge", "show", FILE, "--json"]).output)
    missing = runner.invoke(cli.main, ["--root", str(merge_root), "merge", "show", FILE, "--hunk", "4"])
    assert missing.exit_code == 1 and "no hunk 4" in missing.output


def test_resolve_dry_run_then_apply_then_review(runner: CliRunner, merge_root: Path) -> None:
    base = ["--root", str(merge_root), "merge"]
    dry = runner.invoke(cli.main, [*base, "resolve", FILE, "1=both"])
    assert dry.exit_code == 0, dry.output
    assert "hunk 1: both, lines" in dry.output and "parses: yes" in dry.output and "written: no" in dry.output
    bad = runner.invoke(cli.main, [*base, "resolve", FILE, "first"])
    assert bad.exit_code == 2 and "HUNK=CHOICE" in bad.output
    lossy = runner.invoke(cli.main, [*base, "resolve", FILE, "1=ours", "--apply"])
    assert lossy.exit_code == 0 and "written: yes" in lossy.output
    review = runner.invoke(cli.main, [*base, "review"])
    assert review.exit_code == 1 and "T.venafi lost" in review.output
    assert json.loads(runner.invoke(cli.main, [*base, "review", "--json"]).output)["flagged"]
    git(merge_root, "checkout", "--conflict=merge", "--", FILE)
    good = runner.invoke(cli.main, [*base, "resolve", FILE, "--suggested", "--apply", "--json"])
    assert json.loads(good.output)["written"] is True
    clean = runner.invoke(cli.main, [*base, "review", FILE])
    assert clean.exit_code == 0 and "1 clean" in clean.output


def test_errors_exit_one(runner: CliRunner, merge_root: Path) -> None:
    (merge_root / "plain.groovy").write_text("class P {}\n", encoding="utf-8")
    result = runner.invoke(cli.main, ["--root", str(merge_root), "merge", "resolve", "plain.groovy", "1=ours"])
    assert result.exit_code == 1 and "no conflict hunks" in result.output
    git(merge_root, "merge", "--abort")
    review = runner.invoke(cli.main, ["--root", str(merge_root), "merge", "review"])
    assert review.exit_code == 1 and "not a merge commit" in review.output
