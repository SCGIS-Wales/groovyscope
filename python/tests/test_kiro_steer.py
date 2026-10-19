"""Kiro agents that can call groovyscope but never load its steering: found by doctor, fixed by `kiro steer`."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from click.testing import CliRunner

from groovyscope import cli, kiro_steer
from groovyscope.doctor import steering_check


def write_agent(folder: Path, name: str, **fields: Any) -> Path:
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / f"{name}.json"
    path.write_text(json.dumps({"name": name, **fields}), encoding="utf-8")
    return path


def test_an_agent_reaches_groovyscope_through_its_servers_its_tools_or_the_mcp_files() -> None:
    assert kiro_steer.reaches_groovyscope({"mcpServers": {"groovyscope": {}}}, [])
    assert kiro_steer.reaches_groovyscope({"allowedTools": ["read", "@groovyscope/review"]}, [])
    assert kiro_steer.reaches_groovyscope({"includeMcpJson": True}, ["github", "groovyscope"])
    assert not kiro_steer.reaches_groovyscope({"includeMcpJson": True}, ["github"])
    assert not kiro_steer.reaches_groovyscope({"tools": ["*"]}, ["groovyscope"])  # without includeMcpJson


def test_steering_is_loaded_by_name_or_by_a_steering_glob() -> None:
    assert kiro_steer.loads_steering({"resources": ["file://.kiro/steering/**/*.md"]})
    assert kiro_steer.loads_steering({"resources": ["file:///h/.kiro/steering/groovyscope.md"]})
    assert not kiro_steer.loads_steering({"resources": ["file://README.md"]})
    assert not kiro_steer.loads_steering({"resources": "file://.kiro/steering/groovyscope.md"})
    added = kiro_steer.with_steering({"name": "a", "resources": ["file://README.md", 3]}, ["file://README.md", "x"])
    assert added == {"name": "a", "resources": ["file://README.md", "x"]}
    assert kiro_steer.with_steering({"name": "a"}, ["x"])["resources"] == ["x"]


def test_doctor_names_unsteered_agents_and_kiro_steer_fixes_them(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, home = tmp_path / "ws", tmp_path / "home"
    kiro_home = home / ".kiro"
    (kiro_home / "steering").mkdir(parents=True)
    (kiro_home / "steering" / "groovyscope.md").write_text("steer", encoding="utf-8")
    (root / ".kiro" / "settings").mkdir(parents=True)
    (root / ".kiro" / "settings" / "mcp.json").write_text(
        json.dumps({"mcpServers": {"groovyscope": {"command": "groovyscope"}}}), encoding="utf-8"
    )
    python_agent = write_agent(kiro_home / "agents", "platform-python", includeMcpJson=True, resources=["file://x"])
    write_agent(root / ".kiro" / "agents", "groovy", includeMcpJson=True, resources=["file://.kiro/steering/**/*.md"])
    write_agent(root / ".kiro" / "agents", "plain", tools=["read"])
    (root / ".kiro" / "agents" / "broken.json").write_text("{", encoding="utf-8")
    (root / ".kiro" / "agents" / "list.json").write_text("[]", encoding="utf-8")

    level, _, message = steering_check(root, kiro_home)
    assert level == "warn" and message.startswith("platform-python can call groovyscope")

    monkeypatch.setattr(cli.Path, "home", lambda: home)
    monkeypatch.delenv("KIRO_HOME", raising=False)
    runner = CliRunner()
    dry = runner.invoke(cli.main, ["--root", str(root), "kiro", "steer", "--dry-run", "platform-python", "nope"])
    assert dry.exit_code == 0, dry.output
    assert "nope: not an agent" in dry.output and "would add file://.kiro/steering/groovyscope.md" in dry.output
    assert "resources" in python_agent.read_text(encoding="utf-8") and "steering" not in python_agent.read_text()

    done = runner.invoke(cli.main, ["--root", str(root), "kiro", "steer"])
    assert done.exit_code == 0 and "now loads groovyscope's steering" in done.output
    written = json.loads(python_agent.read_text(encoding="utf-8"))
    assert written["resources"] == [
        "file://x",
        "file://.kiro/steering/groovyscope.md",
        f"file://{(kiro_home / 'steering' / 'groovyscope.md').as_posix()}",
    ]
    assert (python_agent.parent / "platform-python.json.bak").is_file()
    assert steering_check(root, kiro_home)[0] == "ok"
    again = runner.invoke(cli.main, ["--root", str(root), "kiro", "steer"])
    assert "already loads its steering" in again.output
