"""Trust is the user's decision: a repository's own .groovyscope.yaml cannot switch on code-running settings."""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from click.testing import CliRunner

from groovyscope import cli, profiles, trust
from groovyscope.config import load_config, load_config_with_notes

RISKY = """\
workspace: {trust: true}
jdk: {home: ./evil-jdk}
lint: {ruleset: ./rules.groovy, jenkinsRuleset: ./jenkins.groovy}
"""


def test_untrusted_workspace_files_cannot_enable_code_running_settings(tmp_path: Path) -> None:
    home = tmp_path / "home"
    (tmp_path / ".groovyscope.yaml").write_text(RISKY, encoding="utf-8")
    config, notes = load_config_with_notes(tmp_path, None, {}, home)
    assert config["workspace"]["trust"] is False
    assert config["jdk"]["home"] is None
    assert config["lint"]["ruleset"] is None and config["lint"]["jenkinsRuleset"] is None
    assert len(notes) == 4 and all("groovyscope trust" in note for note in notes)

    trust.set_trusted(tmp_path, home, trusted=True)
    trusted, notes = load_config_with_notes(tmp_path, None, {}, home)
    assert trusted["workspace"]["trust"] is True and trusted["jdk"]["home"] == "./evil-jdk" and notes == []
    assert oct((home / trust.TRUST_FILE).stat().st_mode & 0o777) == "0o600"
    assert trust.set_trusted(tmp_path, home, trusted=False) == []
    assert load_config(tmp_path, None, {}, home)["workspace"]["trust"] is False


def test_environment_and_explicit_config_are_the_users_choice(tmp_path: Path) -> None:
    home = tmp_path / "home"
    (tmp_path / ".groovyscope.yaml").write_text(RISKY, encoding="utf-8")
    env = {"GROOVYSCOPE_WORKSPACE__TRUST": "true", "GROOVYSCOPE_JDK__HOME": "/opt/jdk"}
    config, notes = load_config_with_notes(tmp_path, None, env, home)
    assert config["workspace"]["trust"] is True and config["jdk"]["home"] == "/opt/jdk"
    assert all("jdk.home" not in note for note in notes)
    explicit = tmp_path / "mine.yaml"
    explicit.write_text(RISKY, encoding="utf-8")
    chosen, notes = load_config_with_notes(tmp_path, explicit, {}, home)
    assert chosen["workspace"]["trust"] is True and chosen["lint"]["ruleset"] == "./rules.groovy" and notes == []


def test_malformed_files_and_stray_environment(tmp_path: Path) -> None:
    home = tmp_path / "home"
    (home / trust.TRUST_FILE).parent.mkdir(parents=True)
    (home / trust.TRUST_FILE).write_text("not json", encoding="utf-8")
    assert trust.trusted_roots(home) == []
    (home / trust.TRUST_FILE).write_text(json.dumps({"workspaces": [1, str(tmp_path)]}), encoding="utf-8")
    assert trust.trusted_roots(home) == [str(tmp_path)]
    (tmp_path / ".groovyscope.yaml").write_text("workspace: [unclosed\n", encoding="utf-8")
    with pytest.raises(ValueError, match="not valid YAML"):
        load_config(tmp_path, None, {}, home)
    (tmp_path / ".groovyscope.yaml").write_text("{}\n", encoding="utf-8")
    assert load_config(tmp_path, None, {"GROOVYSCOPE_TOKEN": "x"}, home)["workspace"]["trust"] is True  # listed


def test_trust_command(runner: CliRunner, tmp_path: Path, isolated_home: Path) -> None:
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "trust"])
    assert result.exit_code == 0 and "is trusted" in result.output
    assert runner.invoke(cli.main, ["trust", "--list"]).output.strip() == str(tmp_path.resolve())
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "trust", "--revoke"])
    assert "no longer trusted" in result.output and trust.trusted_roots(isolated_home) == []


def test_workspace_profiles_lose_emulation_jars_unless_trusted(tmp_path: Path) -> None:
    home = tmp_path / "home"
    root = tmp_path / "ws"
    workspace_profile = root / ".groovyscope" / "profiles" / "w.yaml"
    workspace_profile.parent.mkdir(parents=True)
    workspace_profile.write_text("name: w\ntarget: {emulationJars: [jars/groovy.jar]}\n", encoding="utf-8")
    user_profile = home / ".groovyscope" / "profiles" / "u.yaml"
    user_profile.parent.mkdir(parents=True)
    user_profile.write_text("name: u\ntarget: {emulationJars: [lib/groovy.jar]}\n", encoding="utf-8")
    found = profiles.discover(root, {}, home)
    untrusted = {p["name"]: p for p in profiles.engine_profiles(found, root, trusted=False)}
    assert untrusted["w"]["target"]["emulationJars"] == []
    assert untrusted["u"]["target"]["emulationJars"] == [str((user_profile.parent / "lib/groovy.jar").resolve())]
    assert profiles.untrusted_jar_notes(found, root)[0].startswith("profile w:")
    trusted = {p["name"]: p for p in profiles.engine_profiles(found, root, trusted=True)}
    assert trusted["w"]["target"]["emulationJars"] == [str((workspace_profile.parent / "jars/groovy.jar").resolve())]
