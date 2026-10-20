"""The impact and callgraph commands against a fake engine RPC."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from click.testing import CliRunner

from groovyscope import cli
from groovyscope import daemon as daemon_lifecycle
from groovyscope.cli import common as cli_common


@pytest.fixture
def engine(
    tmp_path: Path, fake_jdk: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> dict[str, Any]:
    jar = tmp_path / "engine.jar"
    jar.write_bytes(b"x")
    monkeypatch.setenv("JAVA_HOME", str(fake_jdk))
    monkeypatch.setenv("GROOVYSCOPE_JAR", str(jar))
    monkeypatch.setenv("GROOVYSCOPE_SOCKET_DIR", str(tmp_path / "run"))
    monkeypatch.setenv("GROOVYSCOPE_CACHE_DIR", str(tmp_path / "cache"))
    monkeypatch.setenv(cli.CONFIG_ENV, "")
    calls: dict[str, Any] = {"requests": []}
    monkeypatch.setattr(daemon_lifecycle, "ensure_running", lambda *_a, **_k: {"engine": "x"})

    def fake_rpc(socket_file: Path, method: str, params: dict[str, Any], timeout: float) -> Any:
        calls["requests"].append((method, params))
        return calls["response"]

    monkeypatch.setattr(cli_common, "_RPC", fake_rpc)
    return calls


def symbol(name: str, uri: str, line: int, detail: str = "", container: str | None = None) -> dict[str, Any]:
    return {
        "name": name,
        "kind": "method",
        "container": container,
        "detail": detail or name,
        "location": {"uri": uri, "line": line, "character": 4},
    }


def test_impact_prints_a_report(runner: CliRunner, tmp_path: Path, engine: dict[str, Any]) -> None:
    greeter = (tmp_path / "src" / "Greeter.groovy").as_uri()
    main = (tmp_path / "src" / "Main.groovy").as_uri()
    site = {
        "caller": symbol("run", main, 0, "run()", "app.Main"),
        "callee": symbol("greet", greeter, 10),
        "site": {"uri": main, "line": 6, "character": 16},
        "confidence": "INFERRED",
    }
    engine["response"] = [
        {
            "symbol": symbol("greet", greeter, 10, "String greet(String name)", "app.Greeter"),
            "change": "signature",
            "riskScore": 42,
            "riskLevel": "medium",
            "directCallers": [site],
            "transitiveCallers": [],
            "overrides": [symbol("greet", greeter, 20, "String greet()", "app.Other")],
            "tests": [],
            "dynamicUnknowns": [dict(site, confidence="DYNAMIC_UNKNOWN")],
            "suggestedChecks": ["review 1 call site(s) matched by name only"],
            "counts": {"direct": 1, "transitive": 0, "overrides": 1, "tests": 0, "dynamicUnknowns": 1},
        }
    ]
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "impact", "app.Greeter.greet", "--change", "signature"])
    assert result.exit_code == 0, result.output
    assert "app.Greeter.String greet(String name) (src/Greeter.groovy:11:5)" in result.output
    assert "risk: medium (42/100)" in result.output
    assert "direct: src/Main.groovy:7:17 in app.Main.run() (src/Main.groovy:1:5) [INFERRED]" in result.output
    assert "dynamic: src/Main.groovy:7:17" in result.output
    assert "override: app.Other.String greet()" in result.output
    assert "- review 1 call site(s) matched by name only" in result.output
    assert engine["requests"][0] == (
        "groovyscope/impact",
        {"root": str(tmp_path), "change": "signature", "symbol": "app.Greeter.greet"},
    )


def test_impact_by_position_and_json_and_missing(runner: CliRunner, tmp_path: Path, engine: dict[str, Any]) -> None:
    engine["response"] = [{"symbol": symbol("x", "file:///x", 0), "counts": {}}]
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "impact", "src/Main.groovy:7:17", "--json"])
    assert result.exit_code == 0, result.output
    assert json.loads(result.output)[0]["symbol"]["name"] == "x"
    params = engine["requests"][0][1]
    assert params["uri"] == (tmp_path / "src" / "Main.groovy").resolve().as_uri()
    assert params["line"] == 6 and params["character"] == 16

    engine["response"] = []
    missing = runner.invoke(cli.main, ["--root", str(tmp_path), "impact", "nothing.Here"])
    assert missing.exit_code == 2
    assert "no declaration found" in missing.output


def test_impact_rpc_failure(
    runner: CliRunner, tmp_path: Path, engine: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    def broken(*_a: object, **_k: object) -> Any:
        msg = "gone"
        raise RuntimeError(msg)

    monkeypatch.setattr(cli_common, "_RPC", broken)
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "impact", "a.B"])
    assert result.exit_code == 1 and "groovyscope/impact failed: gone" in result.output


def test_callgraph_prints_edges(runner: CliRunner, tmp_path: Path, engine: dict[str, Any]) -> None:
    a = (tmp_path / "A.groovy").as_uri()
    edge = {
        "caller": symbol("run", a, 0, "run()", "A"),
        "callee": symbol("work", a, 5, "int work()", "A"),
        "site": {"uri": a, "line": 2, "character": 0},
        "confidence": "STATIC",
    }
    engine["response"] = {
        "direction": "in",
        "depth": 2,
        "roots": [symbol("work", a, 5)],
        "nodes": [symbol("work", a, 5), symbol("run", a, 0)],
        "edges": [edge],
    }
    result = runner.invoke(
        cli.main, ["--root", str(tmp_path), "callgraph", "A#work", "--depth", "3", "--direction", "in"]
    )
    assert result.exit_code == 0, result.output
    assert "incoming calls, depth 3, 2 node(s), 1 edge(s)" in result.output
    assert "A.run() (A.groovy:1:5) -> A.int work() (A.groovy:6:5) [STATIC]" in result.output
    assert engine["requests"][0][1]["depth"] == 3

    engine["response"] = {"roots": [], "nodes": [], "edges": []}
    assert runner.invoke(cli.main, ["--root", str(tmp_path), "callgraph", "nope"]).exit_code == 2
    engine["response"] = {"roots": [symbol("w", a, 1)], "nodes": [], "edges": []}
    assert json.loads(runner.invoke(cli.main, ["--root", str(tmp_path), "callgraph", "w", "--json"]).output)["roots"]


def test_target_params() -> None:
    root = Path("/ws")
    assert cli.target_params({"root": root}, "app.Greeter.greet") == {"symbol": "app.Greeter.greet"}
    assert cli.target_params({"root": root}, "src/A.groovy:3:4")["line"] == 2
    assert cli.target_params({"root": root}, "weird:thing") == {"symbol": "weird:thing"}
