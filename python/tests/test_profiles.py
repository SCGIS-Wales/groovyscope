"""Runtime profile discovery, validation, steering text and contract drift."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from click.testing import CliRunner

from groovyscope import cli, profiles

REPO = Path(__file__).resolve().parents[2]
EXAMPLE = REPO / "config" / "profiles" / "example-runtime.yaml"
ENGINE_FIXTURE = REPO / "engine" / "src" / "test" / "resources" / "profiles" / "demo-runtime.json"


def write(path: Path, text: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


def test_shipped_example_and_engine_fixture_are_valid() -> None:
    assert profiles.validate_profile(profiles.read_profile(EXAMPLE)) == ()
    assert profiles.validate_profile(json.loads(ENGINE_FIXTURE.read_text(encoding="utf-8"))) == ()


@pytest.mark.parametrize(
    ("profile", "expected"),
    [
        ({}, "<root>: 'name' is a required property"),
        ({"name": "x", "unknown": 1}, "<root>: Additional properties are not allowed ('unknown' was unexpected)"),
        ({"name": "x", "target": {"groovy6Checkers": "maybe"}}, "target.groovy6Checkers: 'maybe' is not one of"),
        ({"name": "x", "constructs": [{"id": "a", "status": "fine"}]}, "constructs.0.status: 'fine' is not one of"),
        ({"name": "x", "constructs": [{"id": "a", "status": "blocked", "syntax": ["goto"]}]}, "constructs.0.syntax.0"),
        ({"name": "bad name"}, "name: 'bad name' does not match"),
    ],
)
def test_validation_reports_location_and_reason(profile: dict[str, Any], expected: str) -> None:
    problems = profiles.validate_profile(profile)
    assert any(problem.startswith(expected) for problem in problems), problems


def test_discovery_order_shadowing_and_errors(tmp_path: Path) -> None:
    root = tmp_path / "ws"
    home = tmp_path / "home"
    write(root / "extra" / "first.yaml", "name: alpha\nguidance: from the configured path\n")
    write(root / ".groovyscope" / "profiles" / "ws.yml", "name: beta\n")
    write(root / ".groovyscope" / "profiles" / "notes.txt", "ignored")
    write(home / ".groovyscope" / "profiles" / "user.json", '{"name": "alpha"}')
    write(home / ".groovyscope" / "profiles" / "broken.yaml", "name: [unclosed\n")
    write(home / ".groovyscope" / "profiles" / "invalid.yaml", "name: gamma\ntarget: {groovy: five}\n")
    write(home / ".groovyscope" / "profiles" / "list.yaml", "- not a mapping\n")
    settings = {"paths": ["extra/first.yaml", "extra/first.yaml", "missing.yaml"], "discover": True}

    found = profiles.discover(root, settings, home)

    assert [p["name"] for p in found["profiles"]] == ["alpha", "beta"]
    assert found["profiles"][0]["path"].endswith("first.yaml")
    assert len(found["shadowed"]) == 1 and "user.json" in found["shadowed"][0]
    assert any("missing.yaml: no such profile file" in e for e in found["errors"])
    assert any("broken.yaml" in e for e in found["errors"])
    assert any("invalid.yaml: target.groovy" in e for e in found["errors"])
    assert any("list.yaml: " in e and "must be a mapping" in e for e in found["errors"])
    assert profiles.engine_profiles(found)[1] == {"name": "beta"}
    lines = profiles.describe(found)
    assert lines[0].startswith("alpha: ") and any(line.startswith("shadowed: ") for line in lines)


def test_discovery_can_be_limited_to_configured_paths(tmp_path: Path) -> None:
    write(tmp_path / "home" / ".groovyscope" / "profiles" / "user.yaml", "name: user\n")
    found = profiles.discover(tmp_path, {"paths": [], "discover": False}, tmp_path / "home")
    assert found == {"profiles": [], "shadowed": [], "errors": []}
    assert profiles.describe(found) == ["no runtime profiles found"]


def test_guidance_only_for_profiles_that_carry_it(tmp_path: Path) -> None:
    write(tmp_path / ".groovyscope" / "profiles" / "a.yaml", "name: a\nguidance: '  Keep it boring.  '\n")
    write(tmp_path / ".groovyscope" / "profiles" / "b.yaml", "name: b\n")
    text = profiles.guidance(profiles.discover(tmp_path, {}, tmp_path / "home"))
    assert text.startswith("# Runtime profiles\n")
    assert "## a\n\nKeep it boring.\n" in text and "## b" not in text
    assert profiles.guidance({"profiles": [], "shadowed": [], "errors": []}) == ""


def test_contract_drift_lists_removed_and_changed_items_only() -> None:
    baseline = {"a.groovy": {"parameter:X": "string", "seam:s": "package-private"}, "gone.groovy": {"class:G": "class"}}
    current = {"a.groovy": {"parameter:X": "list", "parameter:NEW": "string"}}
    assert profiles.contract_drift(baseline, current) == [
        "a.groovy: parameter:X changed from string to list",
        "a.groovy: seam:s is gone (was package-private)",
        "gone.groovy: governed file is gone or no longer governed",
    ]
    assert profiles.contract_drift(current, current) == []


@pytest.fixture
def engine(
    tmp_path: Path, fake_jdk: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> dict[str, Any]:
    jar = tmp_path / "engine.jar"
    jar.write_bytes(b"x")
    monkeypatch.setenv("JAVA_HOME", str(fake_jdk))
    monkeypatch.setenv("GROOVYSCOPE_JAR", str(jar))
    monkeypatch.setenv("GROOVYSCOPE_SOCKET_DIR", str(tmp_path / "run"))
    monkeypatch.setenv(cli.CONFIG_ENV, "")
    state: dict[str, Any] = {"requests": [], "responses": {}}
    monkeypatch.setattr(cli.daemon_lifecycle, "ensure_running", lambda *_a, **_k: {"engine": "x"})

    def fake_rpc(socket_file: Path, method: str, params: dict[str, Any], timeout: float) -> Any:
        state["requests"].append((method, params))
        return state["responses"].get(method, {})

    monkeypatch.setattr(cli, "_RPC", fake_rpc)
    return state


def test_engine_receives_discovered_profiles(
    runner: CliRunner, tmp_path: Path, engine: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    write(tmp_path / ".groovyscope" / "profiles" / "p.yaml", "name: p\ntarget: {groovy: '5.1'}\n")
    write(tmp_path / ".groovyscope" / "profiles" / "bad.yaml", "name: q\nwhat: 1\n")
    engine["responses"]["groovyscope/check"] = {"diagnostics": {}, "summary": {}}
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "check"])
    assert "profile skipped" in result.output and "bad.yaml" in result.output
    handed = json.loads(cli.os.environ[cli.CONFIG_ENV])
    assert handed["profiles"] == [{"name": "p", "target": {"groovy": "5.1"}}]


def test_profile_list_and_validate(runner: CliRunner, tmp_path: Path, clean_env: dict[str, str]) -> None:
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "profile", "list"])
    assert result.exit_code == 0 and "no runtime profiles found" in result.output
    good = write(tmp_path / ".groovyscope" / "profiles" / "good.yaml", "name: good\n")
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "profile", "list"])
    assert result.exit_code == 0 and result.output.startswith("good: ")
    bad = write(tmp_path / ".groovyscope" / "profiles" / "bad.yaml", "name: bad\nextra: 1\n")
    assert runner.invoke(cli.main, ["--root", str(tmp_path), "profile", "list"]).exit_code == 1
    result = runner.invoke(cli.main, ["profile", "validate", str(good), str(bad)])
    assert result.exit_code == 1
    assert f"{good}: ok" in result.output and "Additional properties" in result.output
    scalar = write(tmp_path / "scalar.yaml", "just text\n")
    assert runner.invoke(cli.main, ["profile", "validate", str(scalar)]).exit_code == 1
    write(tmp_path / ".groovyscope.yaml", "profiles: {discover: maybe}\n")
    assert runner.invoke(cli.main, ["--root", str(tmp_path), "profile", "list"]).exit_code == 1


def test_profile_status_command(runner: CliRunner, tmp_path: Path, engine: dict[str, Any]) -> None:
    source = write(tmp_path / "plugins" / "DemoPlugin.groovy", "class DemoPlugin {}\n")
    engine["responses"]["groovyscope/profile"] = {
        "governed": True,
        "profile": "demo-runtime",
        "targetGroovy": "5.1",
        "seams": [],
    }
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "profile", "status", str(source)])
    assert result.exit_code == 0, result.output
    assert "profile: demo-runtime" in result.output and "seams" not in result.output
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "profile", "status", str(source), "--json"])
    assert json.loads(result.output)["targetGroovy"] == "5.1"
    engine["responses"]["groovyscope/profile"] = {"governed": False}
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "profile", "status", str(source)])
    assert "plugins/DemoPlugin.groovy: not governed" in result.output


def test_baseline_save_and_check(runner: CliRunner, tmp_path: Path, engine: dict[str, Any]) -> None:
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "baseline", "check"])
    assert result.exit_code == 1 and "no baseline" in result.output
    engine["responses"]["groovyscope/contracts"] = {"files": {"a.groovy": {"parameter:X": "string"}}}
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "baseline", "save"])
    assert result.exit_code == 0, result.output
    assert "1 contract item(s) in 1 file(s) recorded in .groovyscope/contract-baseline.json" in result.output
    saved = json.loads((tmp_path / ".groovyscope" / "contract-baseline.json").read_text(encoding="utf-8"))
    assert saved == {"files": {"a.groovy": {"parameter:X": "string"}}}
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "baseline", "check"])
    assert result.exit_code == 0 and "contract unchanged" in result.output
    engine["responses"]["groovyscope/contracts"] = {"files": {"a.groovy": {"parameter:X": "list"}}}
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "baseline", "check"])
    assert result.exit_code == 1
    assert "a.groovy: parameter:X changed from string to list" in result.output
