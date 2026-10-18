"""The check and format commands against a fake engine RPC."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from click.testing import CliRunner

from groovyscope import cli


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
    monkeypatch.setattr(cli.daemon_lifecycle, "ensure_running", lambda *_a, **_k: {"engine": "x"})

    def fake_rpc(socket_file: Path, method: str, params: dict[str, Any], timeout: float) -> Any:
        calls["requests"].append((method, params))
        return calls["response"]

    monkeypatch.setattr(cli, "_RPC", fake_rpc)
    return calls


def diagnostic(line: int, severity: int, source: str, code: str, message: str) -> dict[str, Any]:
    return {
        "range": {"start": {"line": line, "character": 4}, "end": {"line": line, "character": 10}},
        "severity": severity,
        "source": source,
        "code": code,
        "message": message,
    }


def test_check_prints_findings_and_fails_on_priority(runner: CliRunner, tmp_path: Path, engine: dict[str, Any]) -> None:
    uri = (tmp_path / "src" / "A.groovy").as_uri()
    engine["response"] = {
        "diagnostics": {
            uri: [
                diagnostic(2, 2, "codenarc", "UnnecessarySemicolon", "Semicolons are unnecessary"),
                diagnostic(0, 3, "codenarc", "UnusedImport", "unused"),
            ]
        },
        "summary": {"errors": 0, "warnings": 1, "information": 1, "files": 1},
    }
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "check", "src/A.groovy", "--include", "lint"])
    assert result.exit_code == 1, result.output
    assert "src/A.groovy:1:5: info [codenarc/UnusedImport] unused" in result.output
    assert "src/A.groovy:3:5: warning [codenarc/UnnecessarySemicolon]" in result.output
    assert "0 error(s), 1 warning(s), 1 info in 1 file(s)" in result.output
    method, params = engine["requests"][0]
    assert method == "groovyscope/check"
    assert params["uris"] == [uri]
    assert params["include"] == ["lint"]
    assert params["root"] == str(tmp_path)
    assert "profile" not in params


def test_check_passes_with_only_low_priority_findings_and_json(
    runner: CliRunner, tmp_path: Path, engine: dict[str, Any]
) -> None:
    uri = (tmp_path / "B.groovy").as_uri()
    engine["response"] = {
        "diagnostics": {uri: [diagnostic(0, 3, "codenarc", "Minor", "m")]},
        "summary": {"errors": 0, "warnings": 0, "information": 1, "files": 1},
    }
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "check", "--profile", "jenkins", "--json"])
    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert payload["findings"][0]["code"] == "Minor"
    assert payload["summary"]["files"] == 1
    assert engine["requests"][0][1]["profile"] == "jenkins"
    assert engine["requests"][0][1]["uris"] == []


def test_check_reports_rpc_failures(
    runner: CliRunner, tmp_path: Path, engine: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    def broken(*_a: object, **_k: object) -> Any:
        msg = "socket gone"
        raise OSError(msg)

    monkeypatch.setattr(cli, "_RPC", broken)
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "check"])
    assert result.exit_code == 1
    assert "groovyscope/check failed: socket gone" in result.output


def test_format_reports_then_applies(runner: CliRunner, tmp_path: Path, engine: dict[str, Any]) -> None:
    target = tmp_path / "src" / "Messy.groovy"
    target.parent.mkdir()
    target.write_text("class A {   \n    int x = 1;\n}\n", encoding="utf-8")
    formatted = "class A {\n    int x = 1\n}\n"
    engine["response"] = {
        "edits": {
            target.as_uri(): [
                {
                    "range": {"start": {"line": 0, "character": 0}, "end": {"line": 3, "character": 0}},
                    "newText": formatted,
                }
            ]
        },
        "changed": 1,
        "checked": 1,
    }
    dry = runner.invoke(cli.main, ["--root", str(tmp_path), "format", "src/Messy.groovy"])
    assert dry.exit_code == 1, dry.output
    assert "src/Messy.groovy: 2 line(s) changed" in dry.output
    assert "1 of 1 file(s) need formatting" in dry.output
    assert target.read_text(encoding="utf-8").startswith("class A {   ")

    applied = runner.invoke(cli.main, ["--root", str(tmp_path), "format", "--apply", "--json"])
    assert applied.exit_code == 0, applied.output
    payload = json.loads(applied.output)
    assert payload["files"][0]["applied"] is True
    assert target.read_text(encoding="utf-8") == formatted

    engine["response"] = {"edits": {}, "changed": 0, "checked": 3}
    clean = runner.invoke(cli.main, ["--root", str(tmp_path), "format"])
    assert clean.exit_code == 0
    assert "0 of 3 file(s) need formatting" in clean.output


def test_daemon_socket_failure_exits_1(
    runner: CliRunner, tmp_path: Path, engine: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    def failing(*_a: object, **_k: object) -> dict[str, Any]:
        msg = "cannot start"
        raise RuntimeError(msg)

    monkeypatch.setattr(cli.daemon_lifecycle, "ensure_running", failing)
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "check"])
    assert result.exit_code == 1
    assert "cannot start" in result.output


def test_engine_view_resolves_bundled_rulesets(tmp_path: Path) -> None:
    effective = {
        "lint": {"ruleset": None, "jenkinsRuleset": "~/my/jenkins.groovy", "failOnPriority": 2, "publish": True}
    }
    view = cli.engine_view({"root": tmp_path}, effective)
    assert view["lint"]["ruleset"].endswith("codenarc/groovyscope.groovy")
    assert Path(view["lint"]["ruleset"]).is_file()
    assert view["lint"]["jenkinsRuleset"] == str(Path("~/my/jenkins.groovy").expanduser())
    assert view["root"] == str(tmp_path)


def test_check_short_and_sarif_formats(runner: CliRunner, tmp_path: Path, engine: dict[str, Any]) -> None:
    uri = (tmp_path / "src" / "A.groovy").as_uri()
    engine["response"] = {
        "diagnostics": {uri: [diagnostic(4, 2, "groovyscope", "GS001", "integer division")]},
        "summary": {},
    }
    short = runner.invoke(cli.main, ["--root", str(tmp_path), "check", "--format", "short"])
    assert "src/A.groovy:5:5: warning: integer division [GS001]" in short.output
    assert engine["requests"][-1][1]["include"] == ["lint", "compile", "typecheck", "practices"]
    sarif = runner.invoke(cli.main, ["--root", str(tmp_path), "check", "--format", "sarif"])
    log = json.loads(sarif.output)
    assert log["runs"][0]["results"][0]["ruleId"] == "GS001"
    assert log["runs"][0]["tool"]["driver"]["rules"][0]["helpUri"].endswith("#23-numeric-pitfalls")


def test_fix_reports_then_applies(runner: CliRunner, tmp_path: Path, engine: dict[str, Any]) -> None:
    target = tmp_path / "src" / "A.groovy"
    target.parent.mkdir(parents=True)
    target.write_text("int x = a / b\n", encoding="utf-8")
    engine["response"] = {
        "edits": {
            target.as_uri(): [
                {
                    "range": {"start": {"line": 0, "character": 8}, "end": {"line": 0, "character": 13}},
                    "newText": "a.intdiv(b)",
                }
            ]
        },
        "applied": {target.as_uri(): ["GS001"]},
        "appliedCounts": {target.as_uri(): {"GS001": 1}},
        "left": {target.as_uri(): {"NoDef": 2}},
        "rejected": {(tmp_path / "src" / "B.groovy").as_uri(): "the fixed text does not parse; nothing applied"},
    }
    dry = runner.invoke(cli.main, ["--root", str(tmp_path), "fix", "src/A.groovy", "--code", "GS001"])
    assert dry.exit_code == 1
    assert "src/A.groovy: GS001 1, 1 line(s)" in dry.output and "src/B.groovy: skipped" in dry.output
    assert "src/A.groovy: left without a safe fix: NoDef 2" in dry.output
    assert engine["requests"][-1] == (
        "groovyscope/fix",
        {"root": str(tmp_path), "uris": [target.as_uri()], "codes": ["GS001"]},
    )
    assert target.read_text(encoding="utf-8") == "int x = a / b\n"
    applied = runner.invoke(cli.main, ["--root", str(tmp_path), "fix", "--apply", "--json"])
    assert applied.exit_code == 0, applied.output
    assert json.loads(applied.output)["files"][0]["applied"] is True
    assert target.read_text(encoding="utf-8") == "int x = a.intdiv(b)\n"


def test_explain(runner: CliRunner, tmp_path: Path, engine: dict[str, Any]) -> None:
    engine["response"] = {
        "code": "GS002",
        "title": "Elvis",
        "why": "0 is valid",
        "fix": "compare with null",
        "link": "https://x#y",
    }
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "explain", "GS002"])
    assert result.exit_code == 0
    assert "GS002: Elvis" in result.output and "see: https://x#y" in result.output
    as_json = runner.invoke(cli.main, ["--root", str(tmp_path), "explain", "GS002", "--json"])
    assert json.loads(as_json.output)["fix"] == "compare with null"
    engine["response"] = None
    missing = runner.invoke(cli.main, ["--root", str(tmp_path), "explain", "Nope"])
    assert missing.exit_code == 1 and "nothing known about Nope" in missing.output


def test_review_changed_renders_a_merge_request_review(
    runner: CliRunner, tmp_path: Path, engine: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    uri = (tmp_path / "src" / "A.groovy").as_uri()
    responses = {
        "groovyscope/check": {
            "diagnostics": {
                uri: [
                    diagnostic(3, 1, "groovyscope", "GS006", "Eval on input"),
                    diagnostic(30, 2, "codenarc", "NoDef", "def"),
                ]
            },
            "summary": {"errors": 1, "warnings": 1, "information": 0, "files": 1},
        },
        "groovyscope/documentSymbols": {
            "symbols": [
                {
                    "name": "A",
                    "kind": "class",
                    "location": {"uri": uri, "range": {"start": {"line": 0}}},
                    "endLine": 40,
                    "children": [
                        {
                            "name": "run",
                            "kind": "method",
                            "location": {"uri": uri, "range": {"start": {"line": 2}}},
                            "endLine": 6,
                        }
                    ],
                }
            ],
            "lines": 41,
        },
        "groovyscope/impact": [
            {
                "symbol": {"name": "run", "kind": "method", "location": None},
                "riskLevel": "high",
                "riskScore": 80,
                "counts": {"direct": 3},
                "tests": [],
                "dynamicUnknowns": [],
                "directCallers": [],
                "transitiveCallers": [],
                "overrides": [],
                "suggestedChecks": [],
            }
        ],
        "groovyscope/findCode": {
            "matches": [
                {
                    "uri": uri,
                    "line": 3,
                    "endLine": 5,
                    "detail": "catch (Exception e)",
                    "snippet": "x",
                    "enclosing": "A.run",
                    "handling": "swallowed",
                }
            ],
            "count": 1,
        },
    }

    calls: list[str] = []

    def fake_rpc(_socket: Path, method: str, _params: dict[str, Any], _timeout: float) -> Any:
        calls.append(method)
        return responses.get(method, {})

    monkeypatch.setattr(cli, "_RPC", fake_rpc)
    monkeypatch.setattr(
        cli.changes,
        "changed_files",
        lambda _root, base, staged=False: {
            "base": base,
            "mode": "staged" if staged else "branch",
            "files": [{"path": "src/A.groovy", "status": "modified", "ranges": [[2, 8]]}],
        },
    )
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "review", "--changed", "--base", "develop"])
    assert result.exit_code == 1, result.output
    assert result.output.startswith("verdict: request changes: 1 blocking finding(s)")
    assert "blocking (1):\n  src/A.groovy:4: [GS006] Eval on input" in result.output
    assert "impact: A#run (method, src/A.groovy) risk high" in result.output or "impact: A.run" in result.output
    assert "added catch: src/A.groovy:4 catch (Exception e)" in result.output
    assert "pre-existing in these files: 1" in result.output
    assert calls[:2] == ["groovyscope/check", "groovyscope/documentSymbols"]
    as_json = runner.invoke(cli.main, ["--root", str(tmp_path), "review", "--changed", "--staged", "--json"])
    payload = json.loads(as_json.output)
    assert payload["mode"] == "staged" and payload["counts"] == {"blocking": 1, "shouldFix": 0, "nits": 0}
    assert [i["symbol"] for i in payload["impact"]] == ["A", "A.run"] and payload["riskyCount"] == 3
    monkeypatch.setattr(cli.changes, "changed_files", lambda *_a, **_k: (_ for _ in ()).throw(ValueError("no git")))
    failed = runner.invoke(cli.main, ["--root", str(tmp_path), "review", "--changed"])
    assert failed.exit_code == 1 and "groovyscope: no git" in failed.output


def test_review_without_changed_groups_by_checklist(runner: CliRunner, tmp_path: Path, engine: dict[str, Any]) -> None:
    uri = (tmp_path / "B.groovy").as_uri()
    engine["response"] = {
        "diagnostics": {uri: [diagnostic(0, 2, "codenarc", "NoDef", "def used")]},
        "summary": {"errors": 0, "warnings": 1, "information": 0, "files": 1},
    }
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "review", "B.groovy"])
    assert result.exit_code == 0, result.output
    assert "Explicit types (1)" in result.output and "B.groovy:1: [NoDef] def used" in result.output


def test_check_imports_sarif_and_coverage_reads_jacoco(
    runner: CliRunner, tmp_path: Path, engine: dict[str, Any]
) -> None:
    sarif = tmp_path / "spotbugs.sarif.json"
    sarif.write_text(
        json.dumps(
            {
                "version": "2.1.0",
                "runs": [
                    {
                        "tool": {"driver": {"name": "SpotBugs"}},
                        "results": [
                            {
                                "ruleId": "NP_NULL",
                                "level": "error",
                                "message": {"text": "null here"},
                                "locations": [
                                    {
                                        "physicalLocation": {
                                            "artifactLocation": {"uri": "src/A.groovy"},
                                            "region": {"startLine": 7, "startColumn": 2},
                                        }
                                    }
                                ],
                            }
                        ],
                    }
                ],
            }
        ),
        encoding="utf-8",
    )
    engine["response"] = {"diagnostics": {}, "summary": {"errors": 0, "warnings": 0, "information": 0, "files": 1}}
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "check", "--import-sarif", str(sarif)])
    assert result.exit_code == 1, result.output
    assert "src/A.groovy:7:2: error [spotbugs/NP_NULL] null here" in result.output
    elsewhere = runner.invoke(
        cli.main, ["--root", str(tmp_path), "check", "src/B.groovy", "--import-sarif", str(sarif)]
    )
    assert elsewhere.exit_code == 0 and "NP_NULL" not in elsewhere.output
    broken = runner.invoke(cli.main, ["--root", str(tmp_path), "check", "--import-sarif", str(tmp_path / "none.sarif")])
    assert "none.sarif" in broken.output and broken.exit_code == 0
    no_report = runner.invoke(cli.main, ["--root", str(tmp_path), "coverage"])
    assert no_report.exit_code == 1 and "no JaCoCo report" in no_report.output
    jacoco = tmp_path / "build" / "reports" / "jacoco" / "test" / "jacocoTestReport.xml"
    jacoco.parent.mkdir(parents=True)
    jacoco.write_text(
        '<report name="d"><package name=""><class name="A" sourcefilename="A.groovy">'
        '<method name="run" line="4"><counter type="LINE" missed="2" covered="0"/></method>'
        '<counter type="LINE" missed="2" covered="2"/></class></package>'
        '<counter type="LINE" missed="2" covered="2"/></report>',
        encoding="utf-8",
    )
    coverage = runner.invoke(cli.main, ["--root", str(tmp_path), "coverage"])
    assert coverage.exit_code == 0, coverage.output
    assert coverage.output.startswith("line coverage 50% (2 of 4 lines)")
    assert "A.groovy:4: uncovered method A.run (2 lines missed)" in coverage.output
    as_json = runner.invoke(cli.main, ["--root", str(tmp_path), "coverage", "--json"])
    assert json.loads(as_json.output)["coverage"]["percent"] == 50
    assert runner.invoke(cli.main, ["--root", str(tmp_path), "coverage", "other"]).output.endswith(
        "no uncovered lines\n"
    )


def test_explain_code_graph_and_naming_commands(
    runner: CliRunner, tmp_path: Path, engine: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    uri = (tmp_path / "src" / "A.groovy").as_uri()
    (tmp_path / "src").mkdir()
    (tmp_path / "src" / "A.groovy").write_text("class A {}\n", encoding="utf-8")
    facts = {
        "kind": "class",
        "name": "app.A",
        "simpleName": "A",
        "package": "app",
        "uri": uri,
        "startLine": 0,
        "endLine": 9,
        "modifiers": [],
        "annotations": [],
        "superClass": None,
        "interfaces": [],
        "fields": [
            {
                "name": "n",
                "type": "int",
                "visibility": "private",
                "final": False,
                "static": False,
                "property": False,
                "initialised": True,
                "line": 1,
            }
        ],
        "methods": [
            {
                "name": "run",
                "signature": "void run()",
                "visibility": "public",
                "static": False,
                "abstract": False,
                "constructor": False,
                "returns": "void",
                "dynamicReturn": False,
                "parameters": [],
                "startLine": 2,
                "endLine": 5,
                "branches": 1,
                "calls": [],
                "callerCount": 0,
                "callers": [],
                "reads": [],
                "writes": ["n"],
                "throws": [],
                "catches": [],
                "externals": ["file"],
            }
        ],
        "responsibilities": [{"verb": "run", "methods": ["run"]}],
        "usedBy": [],
        "uses": [],
        "callerCount": 0,
    }
    responses = {
        "groovyscope/explainCode": {"found": True, "items": [facts]},
        "groovyscope/graph": {
            "nodes": [
                {
                    "id": "app.A",
                    "name": "A",
                    "kind": "class",
                    "package": "app",
                    "uri": uri,
                    "startLine": 0,
                    "endLine": 9,
                    "members": 2,
                    "test": False,
                }
            ],
            "edges": [],
            "truncated": False,
            "totalClasses": 1,
        },
        "groovyscope/check": {
            "diagnostics": {uri: [diagnostic(1, 3, "groovyscope", "GS023", "says nothing")]},
            "summary": {},
        },
    }
    monkeypatch.setattr(cli, "_RPC", lambda _s, method, _p, _t: responses.get(method, {}))
    explained = runner.invoke(cli.main, ["--root", str(tmp_path), "explain-code", "app.A"])
    assert explained.exit_code == 0, explained.output
    assert explained.output.startswith("class app.A (src/A.groovy, lines 1-10)")
    assert "  public void run() lines 3-6, branches 1, callers 0, writes n, externals file" in explained.output
    by_file = runner.invoke(cli.main, ["--root", str(tmp_path), "explain-code", "src/A.groovy", "--json"])
    assert json.loads(by_file.output)["items"][0]["name"] == "app.A"
    responses["groovyscope/explainCode"] = {"found": False, "items": []}
    assert runner.invoke(cli.main, ["--root", str(tmp_path), "explain-code", "Nope"]).exit_code == 2
    drawn = runner.invoke(cli.main, ["--root", str(tmp_path), "graph", "app.A", "--out", "out/classes.drawio"])
    assert drawn.exit_code == 0, drawn.output
    assert drawn.output.startswith("1 class(es), 0 relation(s) written to out/classes.drawio (")
    assert (tmp_path / "out" / "classes.drawio").is_file()
    mermaid = runner.invoke(
        cli.main, ["--root", str(tmp_path), "graph", "--path", "src/A.groovy", "--format", "mermaid"]
    )
    assert mermaid.output.startswith("classDiagram\n    class A\n")
    as_json = runner.invoke(cli.main, ["--root", str(tmp_path), "graph", "--format", "json", "--kinds", "calls,has"])
    assert json.loads(as_json.output)["nodeCount"] == 1
    bad = runner.invoke(cli.main, ["--root", str(tmp_path), "graph", "--format", "json", "--kinds", "nope"])
    assert bad.exit_code == 1 and "unknown edge kind" in bad.output
    responses["groovyscope/explainCode"] = {"found": True, "items": [facts]}
    naming = runner.invoke(cli.main, ["--root", str(tmp_path), "naming", "src/A.groovy"])
    assert naming.exit_code == 0, naming.output
    assert "src/A.groovy:2:5: info [GS023] says nothing" in naming.output and "by code: {'GS023': 1}" in naming.output
    facts_json = runner.invoke(cli.main, ["--root", str(tmp_path), "naming", "src/A.groovy", "--json"])
    assert json.loads(facts_json.output)["declarations"][0]["name"] == "A"


def test_hook_command_checks_written_files_and_gates_stop(
    runner: CliRunner, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    src = tmp_path / "src" / "main" / "groovy"
    src.mkdir(parents=True)
    target = src / "A.groovy"
    target.write_text("class A { def x = 1 / 0 }")
    uri = target.resolve().as_uri()
    finding = {
        "range": {"start": {"line": 0, "character": 10}},
        "severity": 1,
        "source": "groovyc",
        "code": "error",
        "message": "unexpected token",
    }
    seen: list[dict[str, Any]] = []

    def fake_rpc(_socket: Path, method: str, params: dict[str, Any], timeout: float) -> dict[str, Any]:
        seen.append({"method": method, "params": params, "timeout": timeout})
        return {"diagnostics": {uri: [finding]}, "summary": {}}

    monkeypatch.setattr(cli, "_RPC", fake_rpc)
    probes: list[Path] = []
    monkeypatch.setattr(cli, "_PROBE", lambda socket_file: probes.append(socket_file) or {"engine": "x"})
    monkeypatch.setattr(cli, "daemon_socket_for", lambda _obj: pytest.fail("a hook must never start the engine"))
    (tmp_path / ".groovyscope.yaml").write_text("hooks:\n  timeoutSeconds: 9\n")
    payload = json.dumps({"hook_event_name": "PostToolUse", "tool_name": "write", "tool_input": {"path": str(target)}})
    result = runner.invoke(cli.main, ["--root", str(tmp_path), "hook"], input=payload)
    assert result.exit_code == 0, result.output
    assert result.output.startswith("groovyscope checked src/main/groovy/A.groovy: 1 error(s)")
    assert seen[0]["method"] == "groovyscope/check" and seen[0]["timeout"] == 9.0
    assert seen[0]["params"]["include"] == ["compile", "lint", "practices"]

    assert len(probes) == 1

    other = json.dumps({"hook_event_name": "PostToolUse", "tool_name": "read", "tool_input": {"path": str(target)}})
    assert runner.invoke(cli.main, ["--root", str(tmp_path), "hook"], input=other).output == ""
    assert runner.invoke(cli.main, ["--root", str(tmp_path), "hook"], input="{}").output == ""

    stop = json.dumps({"hook_event_name": "Stop"})
    assert runner.invoke(cli.main, ["--root", str(tmp_path), "hook"], input=stop).output == ""  # gate off
    assert len(probes) == 1  # none of those three events touched the engine
    (tmp_path / ".groovyscope.yaml").write_text("hooks:\n  stopGate: true\n")
    monkeypatch.setattr(cli.changes, "default_runner", lambda _c, _d: (0, " M src/main/groovy/A.groovy\0"))
    blocked = runner.invoke(cli.main, ["--root", str(tmp_path), "hook"], input=stop)
    assert json.loads(blocked.output)["decision"] == "block" and seen[-1]["params"]["include"] == ["compile"]

    def slow(_socket: Path, _method: str, _params: dict[str, Any], _timeout: float) -> dict[str, Any]:
        raise RuntimeError("timed out")

    monkeypatch.setattr(cli, "_RPC", slow)
    skipped = runner.invoke(cli.main, ["--root", str(tmp_path), "hook"], input=payload)
    assert skipped.exit_code == 0 and "hook check skipped" in skipped.output

    monkeypatch.setattr(cli, "_PROBE", lambda _socket_file: None)
    unavailable = runner.invoke(cli.main, ["--root", str(tmp_path), "hook"], input=payload)
    assert unavailable.exit_code == 0 and "daemon is not running" in unavailable.output
