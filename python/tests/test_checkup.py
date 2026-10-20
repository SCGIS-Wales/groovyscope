"""`groovyscope checkup`: the post-install report, its safe repairs and the agent block."""

from __future__ import annotations

import json
import os
import time
from pathlib import Path

import pytest

from groovyscope import __version__, checkup, gitignore, kiro


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def item(report: checkup.Report, name: str) -> checkup.Item:
    return next(i for i in report["items"] if i["name"] == name)


def test_fresh_machine_reports_every_fix_without_touching_anything(
    tmp_path: Path, isolated_home: Path, clean_env: dict[str, str]
) -> None:
    write(tmp_path / "build.gradle", "plugins { id 'groovy' }\n")
    report = checkup.build(tmp_path, None, clean_env, isolated_home, fix=False)
    assert item(report, "kiro files")["fix"] == "groovyscope kiro init --global"
    assert item(report, "project")["fix"] == "groovyscope kiro init"
    assert item(report, "trust")["safe"] is False
    assert "needs `groovyscope trust` first" in item(report, "classpath")["message"]
    assert item(report, "benchmark")["fix"] == "groovyscope benchmark path"
    assert item(report, "daemon")["level"] == "info"
    assert not (isolated_home / ".kiro").exists() and not (tmp_path / ".kiro").exists()
    assert len(report["todo"]) >= 4
    assert any(t.startswith("untrusted workspace") for t in report["todo"])


def test_fix_applies_the_safe_repairs_and_leaves_the_decisions(
    tmp_path: Path, isolated_home: Path, clean_env: dict[str, str]
) -> None:
    write(tmp_path / "build.gradle", "plugins { id 'groovy' }\n")
    report = checkup.build(tmp_path, None, clean_env, isolated_home, fix=True)
    assert item(report, "kiro files")["level"] == "ok"
    assert kiro.has_global_setup(isolated_home / ".kiro")
    assert kiro.kiro_files_version(isolated_home / ".kiro") == __version__
    assert item(report, "project")["level"] == "ok" and checkup.has_lsp_entry(tmp_path)
    assert item(report, "benchmark")["level"] == "ok"
    assert (isolated_home / ".groovyscope" / "benchmark" / "codenarc" / ".groovyscope.yaml").is_file()
    assert len(report["repaired"]) == 3
    assert [t.split(".")[0] for t in report["todo"]][:1] == ["untrusted workspace: its "]
    again = checkup.build(tmp_path, None, clean_env, isolated_home, fix=True)
    assert again["repaired"] == []


def test_stale_kiro_files_and_hidden_servers_are_repaired(
    tmp_path: Path, isolated_home: Path, clean_env: dict[str, str]
) -> None:
    home = isolated_home / ".kiro"
    kiro.apply_changes(home, kiro.plan_changes(home, global_scope=True))
    steering = home / "steering" / "groovyscope.md"
    steering.write_text(steering.read_text(encoding="utf-8").replace(__version__, "0.0.1"), encoding="utf-8")
    report = checkup.build(tmp_path, None, clean_env, isolated_home, fix=False)
    assert "written by groovyscope 0.0.1" in item(report, "kiro files")["message"]
    agent = home / "agents" / "groovy.json"
    document = json.loads(agent.read_text(encoding="utf-8"))
    document["tools"] = ["code", "@groovyscope"]
    agent.write_text(json.dumps(document), encoding="utf-8")
    write(
        home / "settings" / "mcp.json",
        json.dumps({"mcpServers": {"groovyscope": {"command": "groovyscope"}, "gitlab": {"command": "x"}}}),
    )
    report = checkup.build(tmp_path, None, clean_env, isolated_home, fix=False)
    assert "hides these MCP servers: gitlab" in item(report, "kiro files")["message"]
    fixed = checkup.build(tmp_path, None, clean_env, isolated_home, fix=True)
    assert fixed["repaired"][0].startswith("Kiro files refreshed")
    assert "*" in json.loads(agent.read_text(encoding="utf-8"))["tools"]
    assert kiro.kiro_files_version(home) == __version__


def test_classpath_states(tmp_path: Path, isolated_home: Path, clean_env: dict[str, str]) -> None:
    write(tmp_path / "pom.xml", "<project/>")
    write(tmp_path / ".groovyscope/classpath.txt", "# header\n/a.jar\n/b.jar\n")
    report = checkup.build(tmp_path, None, clean_env, isolated_home, fix=False)
    assert item(report, "classpath")["message"] == "2 entries from classpath.txt"
    later = time.time() + 10
    os.utime(tmp_path / "pom.xml", (later, later))
    report = checkup.build(tmp_path, None, clean_env, isolated_home, fix=False)
    assert "changed after the classpath was exported" in item(report, "classpath")["message"]
    (tmp_path / ".groovyscope/classpath.txt").unlink()
    write(tmp_path / ".groovyscope.yaml", "workspace:\n  classpath: [/x.jar]\n")
    report = checkup.build(tmp_path, None, clean_env, isolated_home, fix=False)
    assert item(report, "classpath")["level"] == "ok"
    (tmp_path / "pom.xml").unlink()
    (tmp_path / ".groovyscope.yaml").unlink()
    write(tmp_path / "Jenkinsfile", "pipeline {}")
    report = checkup.build(tmp_path, None, clean_env, isolated_home, fix=False)
    assert "no build tool here" in item(report, "classpath")["message"]


def test_outside_a_project_the_workspace_items_are_skipped(
    tmp_path: Path, isolated_home: Path, clean_env: dict[str, str]
) -> None:
    report = checkup.build(tmp_path, None, clean_env, isolated_home, fix=False)
    assert item(report, "project")["level"] == "info"
    assert all(i["name"] not in {"trust", "classpath"} for i in report["items"])


def test_a_crashing_check_becomes_an_item(
    tmp_path: Path, isolated_home: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    def boom(*_args: object, **_kwargs: object) -> object:
        msg = "disk on fire"
        raise RuntimeError(msg)

    monkeypatch.setattr(checkup.benchmark, "status", boom)
    monkeypatch.setattr(checkup, "workspace_items", boom)
    write(tmp_path / "build.gradle", "")
    report = checkup.build(tmp_path, None, clean_env, isolated_home, fix=False)
    assert item(report, "benchmark")["message"] == "could not check: disk on fire"
    assert item(report, "workspace")["message"] == "could not check: disk on fire"


def test_invalid_configuration_is_one_failing_item(
    tmp_path: Path, isolated_home: Path, clean_env: dict[str, str]
) -> None:
    write(tmp_path / "build.gradle", "")
    write(tmp_path / ".groovyscope.yaml", "jdk:\n  minimum: 1\n")
    report = checkup.build(tmp_path, None, clean_env, isolated_home, fix=False)
    assert item(report, "config")["level"] == "fail"


def test_daemon_item_states(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(checkup.daemon, "info", lambda *_a, **_k: {"running": False, "status": None})
    assert checkup.daemon_item(tmp_path, {}, "abc")["level"] == "info"
    monkeypatch.setattr(
        checkup.daemon,
        "info",
        lambda *_a, **_k: {"running": False, "status": None, "outOfMemory": True, "log": "daemon.log"},
    )
    dead = checkup.daemon_item(tmp_path, {}, "abc")
    assert dead["level"] == "warn" and "OutOfMemoryError" in dead["message"] and "daemon.log" in dead["message"]
    monkeypatch.setattr(
        checkup.daemon,
        "info",
        lambda *_a, **_k: {"running": True, "status": {"engine": "1", "memory": {"usedMb": 9, "maxMb": 10}}},
    )
    hot = checkup.daemon_item(tmp_path, {}, "abc")
    assert hot["level"] == "warn" and "9 of 10 MB" in hot["message"]
    monkeypatch.setattr(
        checkup.daemon,
        "info",
        lambda *_a, **_k: {"running": True, "status": {"configHash": "old", "engine": "1"}},
    )
    older = checkup.daemon_item(tmp_path, {}, "abc")
    assert older["level"] == "info" and "reloads it in place" in older["message"] and "fix" not in older
    assert checkup.daemon_item(tmp_path, {}, "old")["message"] == "running (engine 1)"


def test_render_and_agent_block(tmp_path: Path, isolated_home: Path, clean_env: dict[str, str]) -> None:
    write(tmp_path / "build.gradle", "")
    report = checkup.build(tmp_path, None, clean_env, isolated_home, fix=True)
    lines = checkup.render(report)
    assert any(line.startswith("ok    kiro files") for line in lines)
    assert any(line.startswith("repaired: ") for line in lines)
    block = checkup.render_agent(report)
    assert block.startswith(f"groovyscope {__version__} checkup for {tmp_path}: ")
    assert "repaired: Kiro files installed" in block
    assert "todo: untrusted workspace" in block
    assert "ok: " in block
    assert len(checkup.render_agent(report, limit=80)) == 80


def test_profile_guidance_is_empty_on_bad_config(tmp_path: Path, isolated_home: Path) -> None:
    write(tmp_path / ".groovyscope.yaml", "profiles: {discover: maybe}\n")
    assert checkup.profile_guidance(tmp_path, None, {}, isolated_home, global_scope=True) == ""


def test_cache_fix_prunes_surplus_backups_in_the_kiro_home(tmp_path: Path) -> None:
    kiro_global = tmp_path / "kiro"
    steering = kiro_global / "steering"
    steering.mkdir(parents=True)
    for name in ("groovy.md.bak", "groovy.md.bak.1", "groovy.md.bak.2"):
        (steering / name).write_text("x")
    environ = {"GROOVYSCOPE_CACHE_DIR": str(tmp_path / "cache")}
    report, _ = checkup.cache_item(environ, 3, fix=False, kiro_global=kiro_global)
    assert report["level"] == "info" and "1 file(s)" in report["message"]
    checkup.cache_item(environ, 3, fix=True, kiro_global=kiro_global)
    assert sorted(path.name for path in steering.iterdir()) == ["groovy.md.bak", "groovy.md.bak.2"]


def _git_project(tmp_path: Path) -> Path:
    root = tmp_path / "project"
    (root / ".git").mkdir(parents=True)
    (root / "build.gradle").write_text("plugins { id 'groovy' }\n", encoding="utf-8")
    return root


def test_gitignore_item_adds_the_entry_once_when_asked_to_write(tmp_path: Path) -> None:
    root = _git_project(tmp_path)
    (root / ".gitignore").write_text("build/\n", encoding="utf-8")
    environ = {"GROOVYSCOPE_CACHE_DIR": str(tmp_path / "cache")}
    item, done = checkup.gitignore_item(root, None, environ, write=False)
    assert item["level"] == "warn" and item.get("safe") and done == []
    assert (root / ".gitignore").read_text(encoding="utf-8") == "build/\n"
    item, done = checkup.gitignore_item(root, None, environ, write=True)
    assert item["level"] == "ok" and done and ".groovyscope/" in done[0]
    item, done = checkup.gitignore_item(root, None, environ, write=True)
    assert item["level"] == "ok" and done == []
    assert (root / ".gitignore").read_text(encoding="utf-8").count(".groovyscope/") == 1


def test_gitignore_item_honours_the_setting_and_skips_non_projects(tmp_path: Path) -> None:
    root = _git_project(tmp_path)
    (root / ".groovyscope.yaml").write_text("workspace: {gitignore: false}\n", encoding="utf-8")
    item, done = checkup.gitignore_item(root, None, {}, write=True)
    assert item["level"] == "info" and done == [] and not (root / ".gitignore").exists()
    plain = tmp_path / "plain"
    (plain / ".git").mkdir(parents=True)
    item, _ = checkup.gitignore_item(plain, None, {}, write=True)
    assert item["level"] == "info" and not (plain / ".gitignore").exists()


def test_the_agent_checkup_writes_the_gitignore_entry_and_nothing_else(tmp_path: Path) -> None:
    root = _git_project(tmp_path)
    home = tmp_path / "home"
    environ = {"GROOVYSCOPE_CACHE_DIR": str(tmp_path / "cache"), "GROOVYSCOPE_BENCHMARK_DIR": str(tmp_path / "bench")}
    report = checkup.build(root, None, environ, home, fix=False, agent=True)
    assert any(".groovyscope/" in done for done in report["repaired"])
    assert ".groovyscope/" in (root / ".gitignore").read_text(encoding="utf-8")
    assert not (root / ".kiro").exists() and not (home / ".kiro").exists()
    assert "repaired: " in checkup.render_agent(report)


def test_gitignore_item_says_the_entry_waits_for_the_merge(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    (tmp_path / "build.gradle").write_text("apply plugin: 'groovy'\n")
    deferred: gitignore.Outcome = {"state": "deferred", "path": str(tmp_path / ".gitignore")}
    monkeypatch.setattr(gitignore, "ensure", lambda *_a, **_k: deferred)
    item, done = checkup.gitignore_item(tmp_path, None, {}, write=True)
    assert item["level"] == "info" and "merge or rebase in progress ends" in item["message"] and done == []
