from __future__ import annotations

import json
from pathlib import Path

import pytest

from groovyscope.kiro import (
    GLOBAL_FILES,
    KIRO_FILES,
    RETIRED_KEYS,
    apply_changes,
    has_global_setup,
    kiro_home,
    load_template,
    merge_content,
    plan_changes,
    present_files,
    unified_diff,
)


def test_templates_are_well_formed() -> None:
    for name, _ in KIRO_FILES:
        text = load_template(name)
        if name.endswith(".json"):
            json.loads(text)
        else:
            assert text.startswith("---\ninclusion: always")


def test_fresh_init_creates_all_files(tmp_path: Path) -> None:
    changes = plan_changes(tmp_path)
    assert [c["action"] for c in changes] == ["create"] * 4
    written = apply_changes(tmp_path, changes)
    assert len(written) == 4
    lsp = json.loads((tmp_path / ".kiro/settings/lsp.json").read_text(encoding="utf-8"))
    assert lsp["languages"]["groovy"]["command"] == "groovyscope"
    assert all(present_files(tmp_path).values())
    assert not list(tmp_path.rglob("*.bak"))


def test_second_init_is_a_no_op(tmp_path: Path) -> None:
    apply_changes(tmp_path, plan_changes(tmp_path))
    changes = plan_changes(tmp_path)
    assert [c["action"] for c in changes] == ["unchanged"] * 4
    assert apply_changes(tmp_path, changes) == []


def test_merge_preserves_other_languages_and_backs_up(tmp_path: Path) -> None:
    lsp = tmp_path / ".kiro/settings/lsp.json"
    lsp.parent.mkdir(parents=True)
    existing = {"languages": {"python": {"command": "pyright-langserver"}, "groovy": {"command": "old", "extra": 1}}}
    lsp.write_text(json.dumps(existing), encoding="utf-8")
    changes = plan_changes(tmp_path)
    assert changes[0]["action"] == "update"
    apply_changes(tmp_path, changes)
    merged = json.loads(lsp.read_text(encoding="utf-8"))
    assert merged["languages"]["python"] == {"command": "pyright-langserver"}
    assert merged["languages"]["groovy"]["command"] == "groovyscope"
    assert merged["languages"]["groovy"]["extra"] == 1
    assert json.loads(lsp.with_name("lsp.json.bak").read_text(encoding="utf-8")) == existing


def test_apply_without_backup(tmp_path: Path) -> None:
    lsp = tmp_path / ".kiro/settings/lsp.json"
    lsp.parent.mkdir(parents=True)
    lsp.write_text("{}", encoding="utf-8")
    apply_changes(tmp_path, plan_changes(tmp_path), backup=False)
    assert not lsp.with_name("lsp.json.bak").exists()


def test_invalid_existing_json_is_rejected(tmp_path: Path) -> None:
    mcp = tmp_path / ".kiro/settings/mcp.json"
    mcp.parent.mkdir(parents=True)
    mcp.write_text("{not json", encoding="utf-8")
    with pytest.raises(ValueError, match="not valid JSON"):
        plan_changes(tmp_path)
    mcp.write_text("[1, 2]", encoding="utf-8")
    with pytest.raises(ValueError, match="JSON object"):
        plan_changes(tmp_path)
    forced = plan_changes(tmp_path, force=True)
    assert forced[1]["action"] == "update"
    assert json.loads(forced[1]["after"])["mcpServers"]["groovyscope"]["command"] == "groovyscope"


def test_markdown_is_replaced_not_merged(tmp_path: Path) -> None:
    steering = tmp_path / ".kiro/steering/groovyscope.md"
    steering.parent.mkdir(parents=True)
    steering.write_text("old policy\n", encoding="utf-8")
    change = plan_changes(tmp_path)[3]
    assert change["action"] == "update"
    assert change["after"] == load_template("steering/groovyscope.md")
    diff = unified_diff(change)
    assert diff.startswith("--- a/.kiro/steering/groovyscope.md")
    assert "-old policy" in diff


def test_merge_content_normalizes_template_json() -> None:
    out = merge_content(None, '{"a":1}', is_json=True, force=False, path="x.json")
    assert out == '{\n  "a": 1\n}\n'
    assert merge_content("whatever", "md", is_json=False, force=False, path="x.md") == "md"


def test_present_files_partial(tmp_path: Path) -> None:
    (tmp_path / ".kiro/settings").mkdir(parents=True)
    (tmp_path / ".kiro/settings/lsp.json").write_text("{}", encoding="utf-8")
    presence = present_files(tmp_path)
    assert presence[".kiro/settings/lsp.json"] is True
    assert sum(presence.values()) == 1


def test_agent_permissions_from_older_releases_are_retired(tmp_path: Path) -> None:
    """Kiro CLI 2.x ignores an agent with a `permissions` block, so the one groovyscope used to write goes."""
    agent = tmp_path / ".kiro/agents/groovy.json"
    agent.parent.mkdir(parents=True)
    legacy = RETIRED_KEYS["agents/groovy.json"]["permissions"]
    agent.write_text(
        json.dumps({"name": "groovy", "tools": ["read"], "permissions": legacy, "mine": 1}), encoding="utf-8"
    )
    planned = {c["path"]: c for c in plan_changes(tmp_path)}[".kiro/agents/groovy.json"]
    after = json.loads(planned["after"])
    assert "permissions" not in after
    assert after["mine"] == 1
    assert "@groovyscope" in after["allowedTools"] and "shell" in after["toolsSettings"]

    custom = {"rules": [{"capability": "shell", "match": ["make *"], "effect": "allow"}]}
    agent.write_text(json.dumps({"name": "groovy", "permissions": custom}), encoding="utf-8")
    kept = json.loads({c["path"]: c for c in plan_changes(tmp_path)}[".kiro/agents/groovy.json"]["after"])
    assert kept["permissions"] == custom


def test_global_scope_targets_the_kiro_home(tmp_path: Path) -> None:
    assert kiro_home({}, tmp_path) == tmp_path / ".kiro"
    assert kiro_home({"KIRO_HOME": str(tmp_path / "kh")}, tmp_path) == tmp_path / "kh"
    base = tmp_path / ".kiro"
    (base / "settings").mkdir(parents=True)
    (base / "settings" / "mcp.json").write_text(
        json.dumps({"mcpServers": {"other": {"command": "other-mcp"}}}), encoding="utf-8"
    )
    changes = plan_changes(base, global_scope=True)
    assert [c["path"] for c in changes] == [path for _, path in GLOBAL_FILES]
    assert [c["action"] for c in changes] == ["update", "create", "create"]
    written = apply_changes(base, changes)
    assert len(written) == 3
    assert has_global_setup(base) and not has_global_setup(tmp_path / "nowhere")
    mcp = json.loads((base / "settings" / "mcp.json").read_text(encoding="utf-8"))
    assert set(mcp["mcpServers"]) == {"other", "groovyscope"}
    assert (base / "settings" / "mcp.json.bak").is_file()
    agent = json.loads((base / "agents" / "groovy.json").read_text(encoding="utf-8"))
    assert agent["resources"][-1] == f"file://{base.as_posix()}/steering/**/*.md"
    assert [c["action"] for c in plan_changes(base, global_scope=True)] == ["unchanged"] * 3
    assert [c["path"] for c in plan_changes(tmp_path, lsp_only=True, profile_guidance="x")] == [
        ".kiro/settings/lsp.json"
    ]
    (base / "settings" / "mcp.json").write_text("[1]", encoding="utf-8")
    assert not has_global_setup(base)


def test_profile_guidance_gets_its_own_steering_file(tmp_path: Path) -> None:
    changes = plan_changes(tmp_path, profile_guidance="# Runtime profiles\n\nRule.\n")
    extra = changes[-1]
    assert extra["path"] == ".kiro/steering/groovyscope-profiles.md"
    assert extra["after"] == "---\ninclusion: always\n---\n# Runtime profiles\n\nRule.\n"
    assert plan_changes(tmp_path / "kh", global_scope=True, profile_guidance="x")[-1]["path"] == (
        "steering/groovyscope-profiles.md"
    )
    assert len(plan_changes(tmp_path)) == 4
