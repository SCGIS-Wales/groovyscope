"""groovyscope in any Kiro agent: `doctor` names agents with a part missing, `groovyscope kiro attach` adds it."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from click.testing import CliRunner

from groovyscope import checkup, cli, kiro_steer
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


def test_missing_parts_name_server_steering_and_hooks() -> None:
    assert kiro_steer.loads_steering({"resources": ["file://.kiro/steering/**/*.md"]})
    assert not kiro_steer.loads_steering({"resources": "file://.kiro/steering/groovyscope.md"})
    assert kiro_steer.hook_commands({"hooks": {"stop": [{"command": "x"}, "bad", {}], "bad": 1}}) == {"x"}
    assert kiro_steer.hook_commands({"hooks": []}) == set()
    assert kiro_steer.missing_parts({}, []) == ["server", "steering", "hooks"]
    complete = {
        "includeMcpJson": True,
        "resources": ["file://.kiro/steering/groovyscope.md"],
        "hooks": {
            "agentSpawn": [{"command": "groovyscope checkup --agent"}],
            "stop": [{"command": "groovyscope hook"}],
        },
    }
    assert kiro_steer.missing_parts(complete, ["groovyscope"]) == []


def test_the_attachment_never_narrows_or_widens_the_agents_own_tools(tmp_path: Path) -> None:
    listed = kiro_steer.attachment({"tools": ["read", "@python"]}, ["groovyscope"], tmp_path)
    assert listed["tools"] == ["@groovyscope"] and "mcpServers" in listed
    assert all(tool.startswith("@groovyscope/") for tool in listed["allowedTools"]) and listed["allowedTools"]
    assert "tools" not in kiro_steer.attachment({"tools": ["*"]}, [], tmp_path)
    assert "tools" not in kiro_steer.attachment({}, [], tmp_path)  # no list: adding one would hide its tools
    assert "mcpServers" not in kiro_steer.attachment({"includeMcpJson": True}, ["groovyscope"], tmp_path)


@pytest.fixture
def kiro_setup(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> dict[str, Path]:
    root, home = tmp_path / "ws", tmp_path / "home"
    kiro_home = home / ".kiro"
    (kiro_home / "steering").mkdir(parents=True)
    (kiro_home / "steering" / "groovyscope.md").write_text("steer", encoding="utf-8")
    (root / ".kiro" / "settings").mkdir(parents=True)
    (root / ".kiro" / "settings" / "mcp.json").write_text(
        json.dumps({"mcpServers": {"groovyscope": {"command": "groovyscope"}}}), encoding="utf-8"
    )
    monkeypatch.setattr(cli.Path, "home", lambda: home)
    monkeypatch.delenv("KIRO_HOME", raising=False)
    monkeypatch.setattr(cli, "_KIRO", lambda: None)
    return {"root": root, "kiro_home": kiro_home}


def test_doctor_names_incomplete_agents_and_attach_completes_them(kiro_setup: dict[str, Path]) -> None:
    root, kiro_home = kiro_setup["root"], kiro_setup["kiro_home"]
    python_agent = write_agent(
        kiro_home / "agents",
        "platform-python",
        description="Python engineer",
        tools=["*"],
        includeMcpJson=True,
        resources=["file://README.md"],
        hooks={"stop": [{"command": "ruff check ."}]},
    )
    write_agent(root / ".kiro" / "agents", "plain", tools=["read"])
    (root / ".kiro" / "agents" / "broken.json").write_text("{", encoding="utf-8")
    level, _, message = steering_check(root, kiro_home)
    assert level == "warn" and message.startswith("platform-python lacks steering and hooks")

    runner = CliRunner()
    dry = runner.invoke(cli.main, ["--root", str(root), "kiro", "attach", "--dry-run", "platform-python", "nope"])
    assert dry.exit_code == 0, dry.output
    assert "nope: no such agent" in dry.output and "would add steering, hooks" in dry.output
    assert "steering" not in python_agent.read_text(encoding="utf-8")

    done = runner.invoke(cli.main, ["--root", str(root), "kiro", "attach"])
    assert done.exit_code == 0 and "added steering, hooks (v2 format kept)" in done.output, done.output
    written = json.loads(python_agent.read_text(encoding="utf-8"))
    assert written["name"] == "platform-python" and written["description"] == "Python engineer"
    assert written["tools"] == ["*"] and "mcpServers" not in written
    assert (
        written["resources"][0] == "file://README.md" and "file://.kiro/steering/groovyscope.md" in written["resources"]
    )
    assert {"command": "ruff check ."} in written["hooks"]["stop"]
    assert kiro_steer.missing_parts(written, ["groovyscope"]) == []
    assert (
        "@groovyscope/read_symbol" in written["allowedTools"] and "@groovyscope/rename" not in written["allowedTools"]
    )
    assert (python_agent.parent / "platform-python.json.bak").is_file()
    assert steering_check(root, kiro_home)[0] == "ok"
    assert (
        "every agent that calls groovyscope" in runner.invoke(cli.main, ["--root", str(root), "kiro", "attach"]).output
    )
    again = runner.invoke(cli.main, ["--root", str(root), "kiro", "steer", "platform-python"])  # the 0.21.1 alias
    assert "already has groovyscope" in again.output


def test_a_named_agent_gets_the_server_and_a_v3_agent_only_mcp_rules(kiro_setup: dict[str, Path]) -> None:
    root = kiro_setup["root"]
    lone = write_agent(root / ".kiro" / "agents", "lone", tools=["read", "@python"])
    rules = [{"capability": "shell", "match": ["pytest *"], "effect": "allow"}]
    v3 = write_agent(root / ".kiro" / "agents", "modern", permissions={"rules": rules})
    result = CliRunner().invoke(cli.main, ["--root", str(root), "kiro", "attach", "lone", "modern"])
    assert result.exit_code == 0, result.output
    assert "lone (" in result.output and "added server, steering, hooks" in result.output
    written = json.loads(lone.read_text(encoding="utf-8"))
    assert written["tools"] == ["read", "@python", "@groovyscope"] and "groovyscope" in written["mcpServers"]
    modern = json.loads(v3.read_text(encoding="utf-8"))
    assert "tools" not in modern and "allowedTools" not in modern and "groovyscope" in modern["mcpServers"]
    capabilities = [rule["capability"] for rule in modern["permissions"]["rules"]]
    assert capabilities[0] == "shell" and set(capabilities[1:]) == {"mcp"}  # never file or shell rules of ours
    assert "(v3 format kept)" in result.output


def test_the_start_hook_stays_idle_outside_groovy_projects(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    called: list[str] = []
    monkeypatch.setattr(cli.checkup, "build", lambda *_a, **_k: called.append("build"))
    result = CliRunner().invoke(cli.main, ["--root", str(tmp_path), "checkup", "--agent"])
    assert result.exit_code == 0 and result.output.strip() == checkup.IDLE_NOTE and called == []
