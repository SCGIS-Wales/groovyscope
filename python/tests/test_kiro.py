from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

import pytest

from groovyscope import __version__, kiro, kiro_agent, tools_catalog
from groovyscope.kiro import (
    GLOBAL_FILES,
    KIRO_FILES,
    RETIRED_KEYS,
    apply_changes,
    backup_path,
    has_global_setup,
    kiro_files_version,
    kiro_home,
    load_template,
    merge_content,
    plan_changes,
    present_files,
    unified_diff,
    write_atomically,
    written_by,
)


def test_templates_are_well_formed() -> None:
    for name, _ in KIRO_FILES:
        text = load_template(name)
        if name.endswith(".json"):
            json.loads(text)
        elif name.startswith("skills/"):
            assert text.startswith("---\nname: ") and "\ndescription: " in text
        else:
            # Loaded only when Groovy, Gradle or Jenkins files are in play; the groovy agent reads it through its
            # resources, which ignore the front matter, so it always has it.
            assert text.startswith(kiro.STEERING_HEADER)
            assert "inclusion: fileMatch" in kiro.STEERING_HEADER
            for pattern in ("**/*.groovy", "**/*.gradle", "**/Jenkinsfile*"):
                assert f'"{pattern}"' in kiro.STEERING_HEADER


def test_fresh_init_creates_all_files(tmp_path: Path) -> None:
    changes = plan_changes(tmp_path)
    assert [c["action"] for c in changes] == ["create"] * len(KIRO_FILES)
    written = apply_changes(tmp_path, changes)
    assert len(written) == len(KIRO_FILES)
    lsp = json.loads((tmp_path / ".kiro/settings/lsp.json").read_text(encoding="utf-8"))
    assert lsp["languages"]["groovy"]["command"] == "groovyscope"
    assert all(present_files(tmp_path).values())
    assert not list(tmp_path.rglob("*.bak"))


def test_second_init_is_a_no_op(tmp_path: Path) -> None:
    apply_changes(tmp_path, plan_changes(tmp_path))
    changes = plan_changes(tmp_path)
    assert [c["action"] for c in changes] == ["unchanged"] * len(KIRO_FILES)
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
    change = next(c for c in plan_changes(tmp_path) if c["path"] == ".kiro/steering/groovyscope.md")
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
    assert "@groovyscope/find_symbol" in after["allowedTools"] and "shell" in after["toolsSettings"]
    assert "@groovyscope" not in after["allowedTools"]  # the bare entry allowed the mutating tools too

    custom = {"rules": [{"capability": "shell", "match": ["make *"], "effect": "allow"}]}
    agent.write_text(json.dumps({"name": "groovy", "permissions": custom}), encoding="utf-8")
    kept = json.loads({c["path"]: c for c in plan_changes(tmp_path)}[".kiro/agents/groovy.json"]["after"])
    assert kept["permissions"]["rules"][0] == custom["rules"][0]
    assert kiro_agent.v3_rules()[0] in kept["permissions"]["rules"]
    assert {"capability": "mcp", "match": ["groovyscope/*"], "effect": "allow"} not in kept["permissions"]["rules"]
    assert "tools" not in kept  # a V3-only agent stays V3-only


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
    assert [c["action"] for c in changes] == ["update", *["create"] * (len(GLOBAL_FILES) - 1)]
    written = apply_changes(base, changes)
    assert len(written) == len(GLOBAL_FILES)
    assert has_global_setup(base) and not has_global_setup(tmp_path / "nowhere")
    mcp = json.loads((base / "settings" / "mcp.json").read_text(encoding="utf-8"))
    assert set(mcp["mcpServers"]) == {"other", "groovyscope"}
    assert (base / "settings" / "mcp.json.bak").is_file()
    agent = json.loads((base / "agents" / "groovy.json").read_text(encoding="utf-8"))
    assert agent["resources"][-1] == f"file://{base.as_posix()}/steering/**/*.md"
    assert [c["action"] for c in plan_changes(base, global_scope=True)] == ["unchanged"] * len(GLOBAL_FILES)
    assert [c["path"] for c in plan_changes(tmp_path, lsp_only=True, profile_guidance="x")] == [
        ".kiro/settings/lsp.json"
    ]
    (base / "settings" / "mcp.json").write_text("[1]", encoding="utf-8")
    assert not has_global_setup(base)


def test_profile_guidance_gets_its_own_steering_file(tmp_path: Path) -> None:
    changes = plan_changes(tmp_path, profile_guidance="# Runtime profiles\n\nRule.\n")
    extra = changes[-1]
    assert extra["path"] == ".kiro/steering/groovyscope-profiles.md"
    assert extra["after"] == kiro.STEERING_HEADER + "# Runtime profiles\n\nRule.\n"
    assert plan_changes(tmp_path / "kh", global_scope=True, profile_guidance="x")[-1]["path"] == (
        "steering/groovyscope-profiles.md"
    )
    assert len(plan_changes(tmp_path)) == len(KIRO_FILES)


def fake_kiro(log: list[list[str]], *, upgrade: bool = True, verdict: str = "would upgrade") -> Any:
    """A kiro-cli stand-in: `--version`, `agent upgrade --help`, `--dry-run` with a verdict, and a real rewrite."""

    def run(arguments: list[str]) -> tuple[int, str]:
        log.append(list(arguments))
        if arguments == ["--version"]:
            return (0, "kiro-cli 2.32.0\n")
        if arguments[:3] == ["agent", "upgrade", "--help"]:
            return (0, "Upgrade agent configs to the universal format both engines load.") if upgrade else (2, "")
        if "--dry-run" in arguments:
            return (0, f"  = {verdict}  groovy.json\n")
        if arguments[:3] == ["agent", "upgrade", "--no-backup"]:
            target = Path(arguments[3]) / "groovy.json"
            agent = json.loads(target.read_text(encoding="utf-8"))
            agent["permissions"] = {"rules": [{"capability": "fs_read", "effect": "allow"}]}
            agent["kiroUpgrade"] = {"revision": 2, "fingerprint": "sha256:x", "source": "sha256:y"}
            target.write_text(json.dumps(agent), encoding="utf-8")
            return (0, "1 upgraded")
        return (1, "unexpected")

    return run


def test_new_agent_is_universal_when_kiro_can_write_it(tmp_path: Path) -> None:
    log: list[list[str]] = []
    change = {c["path"]: c for c in plan_changes(tmp_path, run=fake_kiro(log))}[".kiro/agents/groovy.json"]
    agent = json.loads(change["after"])
    assert agent["kiroUpgrade"]["revision"] == 2 and "tools" in agent and "permissions" in agent
    assert kiro_agent.kiro_version(fake_kiro([])) == "2.32.0"
    assert kiro_agent.kiro_version(None) is None
    without = {c["path"]: c for c in plan_changes(tmp_path, run=fake_kiro([], upgrade=False))}
    assert "kiroUpgrade" not in json.loads(without[".kiro/agents/groovy.json"]["after"])
    v2_only = {c["path"]: c for c in plan_changes(tmp_path, run=None)}
    assert "kiroUpgrade" not in json.loads(v2_only[".kiro/agents/groovy.json"]["after"])


def test_existing_formats_are_kept(tmp_path: Path) -> None:
    agent_file = tmp_path / ".kiro/agents/groovy.json"
    agent_file.parent.mkdir(parents=True)
    ours = json.loads(load_template("agents/groovy.json"))

    # V2 with the user's own tool and resource: stays V2, user entries kept
    agent_file.write_text(json.dumps({**ours, "tools": ["read", "my_tool"], "resources": ["file://NOTES.md"]}))
    change = {c["path"]: c for c in plan_changes(tmp_path, run=fake_kiro([]))}[".kiro/agents/groovy.json"]
    agent = json.loads(change["after"])
    assert "kiroUpgrade" not in agent
    assert agent["tools"] == ["read", "my_tool", "*"]  # the user's entries kept, every tool and MCP server exposed
    assert agent["resources"][0] == "file://NOTES.md" and "file://AGENTS.md" in agent["resources"]

    # universal and in sync: left byte for byte
    stamped = {**ours, "permissions": {"rules": []}, "kiroUpgrade": {"revision": 2, "fingerprint": "f"}}
    text = json.dumps(stamped, indent=2) + "\n"
    agent_file.write_text(text)
    log: list[list[str]] = []
    change = {c["path"]: c for c in plan_changes(tmp_path, run=fake_kiro(log, verdict="already universal"))}
    assert change[".kiro/agents/groovy.json"]["action"] == "unchanged"

    # universal but out of sync: re-stamped by kiro-cli
    agent_file.write_text(json.dumps({**stamped, "allowedTools": ["read"]}))
    change = {c["path"]: c for c in plan_changes(tmp_path, run=fake_kiro([], verdict="would upgrade"))}
    agent = json.loads(change[".kiro/agents/groovy.json"]["after"])
    assert agent["kiroUpgrade"]["fingerprint"] == "sha256:x" and "@groovyscope/check" in agent["allowedTools"]

    # a newer stamp groovyscope does not know: untouched, with a note
    future = json.dumps({**ours, "kiroUpgrade": {"revision": 9}})
    agent_file.write_text(future)
    change = {c["path"]: c for c in plan_changes(tmp_path, run=fake_kiro([]))}[".kiro/agents/groovy.json"]
    assert change["action"] == "unchanged" and "unknown format" in change["note"]

    # managed externally: untouched
    agent_file.write_text(json.dumps(ours))
    change = {c["path"]: c for c in plan_changes(tmp_path, run=fake_kiro([], verdict="managed externally"))}
    assert change[".kiro/agents/groovy.json"]["action"] == "unchanged"


def test_requested_formats_convert(tmp_path: Path) -> None:
    agent_file = tmp_path / ".kiro/agents/groovy.json"
    agent_file.parent.mkdir(parents=True)
    ours = json.loads(load_template("agents/groovy.json"))
    agent_file.write_text(json.dumps({**ours, "permissions": {"rules": []}, "kiroUpgrade": {"revision": 2}}))
    to_v2 = {c["path"]: c for c in plan_changes(tmp_path, run=None, agent_format="v2")}[".kiro/agents/groovy.json"]
    assert "permissions" not in json.loads(to_v2["after"]) and "converted from universal" in to_v2["note"]
    to_v3 = {c["path"]: c for c in plan_changes(tmp_path, run=None, agent_format="v3")}[".kiro/agents/groovy.json"]
    v3 = json.loads(to_v3["after"])
    assert "kiroUpgrade" not in v3 and {"capability": "fs_read", "effect": "allow"} in v3["permissions"]["rules"]
    agent_file.unlink()
    fresh = {c["path"]: c for c in plan_changes(tmp_path, agent_format="v3")}[".kiro/agents/groovy.json"]
    assert "tools" not in json.loads(fresh["after"])
    universal = {c["path"]: c for c in plan_changes(tmp_path, run=None, agent_format="universal")}
    assert "cannot write the universal format" in universal[".kiro/agents/groovy.json"]["note"]


def test_classify_and_helpers() -> None:
    assert kiro_agent.classify("not json", None) == "unknown"
    assert kiro_agent.classify("[1]", None) == "unknown"
    assert kiro_agent.classify('{"permissions": {}}', None) == "v3"
    assert kiro_agent.classify('{"tools": []}', fake_kiro([], verdict="v3-only")) == "v3"
    assert kiro_agent.classify('{"kiroUpgrade": {"revision": "x"}}', None) == "unknown"
    assert kiro_agent.classify('{"tools": []}', lambda _a: (1, "")) == "v2"
    assert kiro_agent.union("x", ["a"]) == ["a"]
    assert kiro_agent.get_path({"a": 1}, ["a", "b"]) is None
    assert kiro_agent.upgrade("{}", None) is None
    assert kiro_agent.upgrade("{}", lambda _a: (1, "")) is None
    assert kiro_agent.upgrade("{}", lambda _a: (0, "")) is None  # nothing stamped
    assert kiro_agent.merge_v3({"permissions": "odd"})["permissions"]["rules"][0]["capability"] == "mcp"
    assert kiro_agent.kiro_runner(lambda _n: None) is None
    run = kiro_agent.kiro_runner(lambda _n: "/bin/echo")
    assert run is not None and run(["hello"]) == (0, "hello\n")
    broken = kiro_agent.kiro_runner(lambda _n: "/nonexistent/kiro-cli")
    assert broken is not None and broken(["--version"])[0] == -1


def test_writes_are_atomic_and_backups_never_overwritten(tmp_path: Path) -> None:
    target = tmp_path / "a" / "file.json"
    write_atomically(target, "one")
    target.chmod(0o640)
    write_atomically(target, "two")
    assert target.read_text() == "two" and (target.stat().st_mode & 0o777) == 0o640
    assert not list(target.parent.glob(".*groovyscope-tmp"))
    target.with_name("file.json.bak").write_text("old")
    assert backup_path(target).name == "file.json.bak.1"


def test_hidden_servers_and_server_listing(tmp_path: Path) -> None:
    global_mcp = tmp_path / "g.json"
    global_mcp.write_text(json.dumps({"mcpServers": {"gitlab": {}, "groovyscope": {}, "old": {"disabled": True}}}))
    project_mcp = tmp_path / "p.json"
    project_mcp.write_text(json.dumps({"mcpServers": {"jira": {}, "gitlab": {}}}))
    servers = kiro_agent.mcp_servers([global_mcp, project_mcp, tmp_path / "missing.json"])
    assert servers == ["gitlab", "groovyscope", "jira"]
    assert kiro_agent.hidden_servers({"tools": ["read", "@groovyscope", "@jira/search"]}, servers) == ["gitlab"]
    assert kiro_agent.hidden_servers({"tools": ["*"]}, servers) == []
    assert kiro_agent.hidden_servers({"permissions": {}}, servers) == []


def test_steering_is_stamped_with_the_package_version(tmp_path: Path) -> None:
    text = load_template("steering/groovyscope.md")
    assert "{{version}}" not in text and f"written by groovyscope {__version__}" in text
    assert written_by(text) == __version__ and written_by("plain") is None
    home = tmp_path / "kh"
    assert kiro_files_version(home) is None
    apply_changes(home, plan_changes(home, global_scope=True))
    assert kiro_files_version(home) == __version__


def test_agent_hooks_merge_as_a_union_and_reach_v3_agents() -> None:
    template = load_template("agents/groovy.json")
    ours = json.loads(template)
    assert ours["hooks"]["agentSpawn"][0]["command"] == "groovyscope checkup --agent"
    mine = {"name": "groovy", "tools": ["*"], "hooks": {"agentSpawn": [{"command": "echo hi"}]}}
    merged = json.loads(kiro_agent.merge_agent(json.dumps(mine), template, requested="v2", run=None)["text"])
    assert [h["command"] for h in merged["hooks"]["agentSpawn"]] == ["echo hi", "groovyscope checkup --agent"]
    v3 = {"name": "groovy", "permissions": {"rules": []}, "hooks": {"userPromptSubmit": [{"command": "x"}]}}
    merged_v3 = json.loads(kiro_agent.merge_agent(json.dumps(v3), template, requested="auto", run=None)["text"])
    assert merged_v3["hooks"]["userPromptSubmit"] == [{"command": "x"}, {"command": "groovyscope hook"}]
    assert merged_v3["hooks"]["preToolUse"] == [{"matcher": "write", "command": "groovyscope hook"}]
    assert merged_v3["hooks"]["agentSpawn"][0]["command"] == "groovyscope checkup --agent"
    assert merged_v3["welcomeMessage"].startswith("Groovy agent ready")
    assert "tools" not in merged_v3


def test_approval_rules_follow_the_tool_catalogue() -> None:
    """Kiro ignores readOnlyHint, so the agent files carry the split: read-only tools allowed, mutating ones ask."""
    allow, ask, reads, shell = kiro_agent.v3_rules()
    assert allow["effect"] == "allow" and allow["match"] == [
        f"groovyscope/{n}" for n in tools_catalog.read_only_tools()
    ]
    assert ask["effect"] == "ask" and set(ask["match"]) == {
        "groovyscope/fix",
        "groovyscope/rename",
        "groovyscope/edit_symbol",
        "groovyscope/change_signature",
        "groovyscope/extract_method",
        "groovyscope/move_member",
        "groovyscope/format",
        "groovyscope/refresh_classpath",
        "groovyscope/relationship_graph",
        "groovyscope/resolve_conflicts",
    }
    assert reads == {"capability": "fs_read", "effect": "allow"} and shell["capability"] == "shell"
    assert set(tools_catalog.read_only_tools()) | set(tools_catalog.mutating_tools()) == set(tools_catalog.TOOL_INFO)
    template = json.loads(load_template("agents/groovy.json"))
    assert template["allowedTools"] == ["read", "grep", "glob", "code", *tools_catalog.v2_allowed_entries()]
    # The reviewer is read only in what it can load as well as in what runs unasked: no bare server entry, which
    # would hand it fix, format, rename and the classpath export.
    reviewer = json.loads(load_template("agents/groovy-reviewer.json"))
    expected = ["read", "code", *tools_catalog.v2_allowed_entries()]
    assert reviewer["tools"] == expected and reviewer["allowedTools"] == expected
    assert kiro_agent.major_version("3.0.1") == 3 and kiro_agent.major_version("2.32.0") == 2
    assert kiro_agent.major_version(None) is None and kiro_agent.major_version("beta") is None


def test_bare_groovyscope_entry_and_blanket_rule_are_replaced() -> None:
    """Files written by earlier releases allowed every groovyscope tool; the merge narrows them, keeping the
    user's own entries."""
    template = load_template("agents/groovy.json")
    mine = {"name": "groovy", "tools": ["*"], "allowedTools": ["read", "@groovyscope", "@jira/search"]}
    merged = json.loads(kiro_agent.merge_agent(json.dumps(mine), template, requested="v2", run=None)["text"])
    assert "@groovyscope" not in merged["allowedTools"]
    assert merged["allowedTools"][:3] == ["read", "@jira/search", "grep"]
    assert "@groovyscope/fix" not in merged["allowedTools"] and "@groovyscope/find_code" in merged["allowedTools"]
    blanket = {"capability": "mcp", "match": ["groovyscope/*"], "effect": "allow"}
    custom = {"capability": "shell", "match": ["make *"], "effect": "allow"}
    v3 = kiro_agent.merge_v3({"permissions": {"rules": [blanket, custom]}})
    rules = v3["permissions"]["rules"]
    assert blanket not in rules and rules[0] == custom and kiro_agent.v3_rules()[1] in rules


def test_agent_template_carries_the_write_and_stop_hooks_and_the_reviewer_is_read_only() -> None:
    agent = json.loads(load_template("agents/groovy.json"))
    assert agent["hooks"]["postToolUse"] == [{"matcher": "write", "command": "groovyscope hook"}]
    assert agent["hooks"]["stop"] == [{"command": "groovyscope hook"}]
    mine = {"name": "groovy", "tools": ["*"], "hooks": {"stop": [{"command": "my-gate"}]}}
    merged = json.loads(
        kiro_agent.merge_agent(json.dumps(mine), load_template("agents/groovy.json"), requested="v2", run=None)["text"]
    )
    assert [h["command"] for h in merged["hooks"]["stop"]] == ["my-gate", "groovyscope hook"]
    reviewer = json.loads(load_template("agents/groovy-reviewer.json"))
    assert (
        reviewer["name"] == "groovy-reviewer" and "write" not in reviewer["tools"] and "shell" not in reviewer["tools"]
    )
    assert "hooks" not in reviewer
    assert all(entry.startswith("@groovyscope/") for entry in reviewer["allowedTools"][2:])
    assert (
        "@groovyscope/fix" not in reviewer["allowedTools"] and "@groovyscope/review_changes" in reviewer["allowedTools"]
    )
    assert ("agents/groovy-reviewer.json", ".kiro/agents/groovy-reviewer.json") in KIRO_FILES


def test_repair_keeps_what_the_user_tuned_on_the_mcp_server_entry() -> None:
    template = load_template("mcp.json")
    mine = {
        "mcpServers": {
            "groovyscope": {
                "command": "old-groovyscope",
                "args": ["old"],
                "timeout": 300000,
                "disabledTools": ["coverage_gaps", "relationship_graph"],
                "autoApprove": ["check"],
                "env": {"GROOVYSCOPE_LOG": "debug", "OTHER": "kept"},
            },
            "jira": {"command": "jira-mcp"},
        }
    }
    merged = json.loads(
        merge_content(json.dumps(mine), template, is_json=True, force=False, path="mcp.json", template_name="mcp.json")
    )
    entry = merged["mcpServers"]["groovyscope"]
    assert entry["command"] == "groovyscope" and entry["args"] == ["mcp"]  # ours
    assert entry["timeout"] == 300000 and entry["disabledTools"] == ["coverage_gaps", "relationship_graph"]
    assert entry["autoApprove"] == ["check"]
    assert entry["env"] == {"GROOVYSCOPE_LOG": "debug", "OTHER": "kept"}
    assert merged["mcpServers"]["jira"] == {"command": "jira-mcp"}
    fresh = json.loads(
        merge_content(None, template, is_json=True, force=False, path="mcp.json", template_name="mcp.json")
    )
    assert fresh["mcpServers"]["groovyscope"]["timeout"] == 120000


DANGEROUS_COMMANDS = (
    "groovyscope trust",
    "groovyscope trust --yes-i-am-the-user",
    "groovyscope classpath",
    "groovyscope jdk install",
    "groovyscope fetch",
    "groovyscope kiro init",
    "groovyscope fix --apply",
    "groovyscope fix --changed --apply",
    "groovyscope format --apply",
    "groovyscope checkup --fix",
    "groovyscope graph --out /tmp/x.drawio",
    "groovyscope doctor --bundle /tmp/x.zip",
    "groovyscope daemon stop",
    "groovyscope check ; groovyscope trust",
    "groovyscope check $(groovyscope trust)",
    "groovyscope check `id`",
    "groovyscope check a && rm -rf ~",
    "groovyscope check a | sh",
    "groovyscope check > ~/.bashrc",
    "./gradlew foo",
    "./gradlew test; curl evil",
    "git diff --output=x",
    "git diff --output x",
    "git diff --ext-diff",
    # a glob the shell expands into options named by files in the repository (`--apply`, `--code=GS001`)
    "groovyscope fix *",
    "groovyscope fix [-]-apply",
    "groovyscope checkup *",
    "groovyscope check ?",
    "git diff *",
    # verify runs the project's build: the agent asks first
    "groovyscope verify",
    "groovyscope verify --failed",
    # graph without a stdout format writes a draw.io file
    "groovyscope graph Store",
    "./gradlew test --scan",
    "./gradlew test --write-locks",
    "./gradlew test --init-script x.gradle",
)
SAFE_COMMANDS = (
    "groovyscope check src/main/groovy/A.groovy --format short",
    "groovyscope review --changed --base origin/main",
    "groovyscope graph --format mermaid Store --depth 2",
    "groovyscope fix --changed --code GS001",
    "groovyscope explain-code Store#load",
    "groovyscope daemon status",
    "./gradlew test --info",
    "git status",
    "git diff origin/main...HEAD -- src",
)


def test_the_agent_may_run_only_read_only_commands_and_tests() -> None:
    patterns = tools_catalog.safe_shell_commands()
    allowed = [command for command in DANGEROUS_COMMANDS if any(re.fullmatch(p, command) for p in patterns)]
    assert allowed == []
    assert all(any(re.fullmatch(p, command) for p in patterns) for command in SAFE_COMMANDS)
    repo = Path(__file__).resolve().parents[2]
    package = repo / "python" / "src" / "groovyscope" / "_templates"
    for path in (package / "kiro/agents/groovy.json", repo / "kiro/agents/groovy.json"):
        agent = json.loads(path.read_text(encoding="utf-8"))
        assert agent["toolsSettings"]["shell"]["allowedCommands"] == list(patterns)
    rules = kiro_agent.v3_rules()
    shell = next(rule for rule in rules if rule["capability"] == "shell")
    assert shell["match"] == list(tools_catalog.SAFE_SHELL_GLOBS)
    # V3 matches globs and it is not known whether it splits `a; b` first: exact commands only, no wildcard
    assert not any(set(glob) & set("*?[]{}") for glob in shell["match"])
    assert not any(
        glob.startswith(("groovyscope trust", "groovyscope classpath", "groovyscope fix")) for glob in shell["match"]
    )


def test_merge_retires_the_broad_shell_entries_of_earlier_releases() -> None:
    old = {
        "toolsSettings": {"shell": {"allowedCommands": ["\\./gradlew .*", "groovyscope .*", "git diff.*", "make lint"]}}
    }
    template = {"toolsSettings": {"shell": {"allowedCommands": ["git status"]}}}
    merged = kiro_agent.merge_v2(old, template)
    assert merged["toolsSettings"]["shell"]["allowedCommands"] == ["make lint", "git status"]
    retired = {"permissions": {"rules": [dict(kiro_agent.RETIRED_V3_RULES[1])]}}
    rules = kiro_agent.merge_v3(retired)["permissions"]["rules"]
    assert kiro_agent.RETIRED_V3_RULES[1] not in rules
