"""`groovyscope checkup`: the post-install report, its safe repairs and the agent block."""

from __future__ import annotations

import json
import os
import time
from pathlib import Path

import pytest

from groovyscope import __version__, checkup, kiro


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
