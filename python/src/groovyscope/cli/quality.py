"""Code quality commands: check, hook, review, verify, test results, coverage, format, fix, explain, naming and the
contract baseline."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import time
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any

import click

from groovyscope import (
    changes,
    gitignore,
    hooks,
    practices,
    process,
    profiles,
    reports,
    test_results,
    verify,
)
from groovyscope import config as config_module
from groovyscope import daemon as daemon_lifecycle
from groovyscope import unused as unused_module
from groovyscope.cli import common
from groovyscope.config import load_config
from groovyscope.edits import (
    file_edits,
    fix_outcome,
    settle,
)
from groovyscope.render import compact_json


@common.main.command()
@click.argument("paths", nargs=-1, type=click.Path(path_type=Path))
@click.option(
    "--profile",
    type=click.Choice(["default", "jenkins", "gradle"]),
    default=None,
    help="Rule set profile (default: by file).",
)
@click.option(
    "--include", default="lint,compile,typecheck,practices", show_default=True, help="Comma-separated analyses."
)
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output (same as --format json).")
@click.option(
    "--format",
    "output_format",
    type=click.Choice(["text", "json", "short", "sarif"]),
    default="text",
    show_default=True,
    help="short: file:line:col: severity: message [code]; sarif: SARIF 2.1.0 for code scanning.",
)
@click.option(
    "--import-sarif",
    "sarif_paths",
    multiple=True,
    type=click.Path(path_type=Path, dir_okay=False),
    help="Merge findings from a SARIF log (SpotBugs...), in addition to reports.sarif and the build's default.",
)
@click.pass_obj
def check(
    obj: dict[str, Any],
    *,
    paths: tuple[Path, ...],
    profile: str | None,
    include: str,
    as_json: bool,
    output_format: str,
    sarif_paths: tuple[Path, ...],
) -> None:
    """Lint, compile, type-check and practice-check Groovy files (the whole workspace when no PATHS are given)."""
    socket_file, effective = common.daemon_socket_for(obj)
    params: dict[str, Any] = {
        "root": str(obj["root"]),
        "uris": common.to_uris(obj["root"], paths),
        "include": [part.strip() for part in include.split(",") if part.strip()],
    }
    if profile:
        params["profile"] = profile
    result = common.engine_call(socket_file, "groovyscope/check", params)
    rows = common.finding_rows(result.get("diagnostics", {}), obj["root"])
    rows += common.imported_rows(obj["root"], effective, sarif_paths, paths)
    summary = result.get("summary", {})
    found = unused_module.summary(rows, limit=0)
    if as_json or output_format == "json":
        click.echo(compact_json({"findings": rows, "summary": summary, "unused": found}))
    elif output_format == "short":
        for line in practices.short_lines(rows):
            click.echo(line)
    elif output_format == "sarif":
        click.echo(json.dumps(practices.sarif(rows), indent=2))
    else:
        for row in rows:
            location = f"{row['path']}:{row['line']}:{row['column']}"
            click.echo(f"{location}: {row['severity']} [{row['source']}/{row['code']}] {row['message']}")
        click.echo(
            f"{summary.get('errors', 0)} error(s), {summary.get('warnings', 0)} warning(s), "
            f"{summary.get('information', 0)} info in {summary.get('files', 0)} file(s)"
        )
        if found["total"]:
            click.echo(f"UNUSED: {unused_module.counted(found['byKind'])}; `groovyscope unused` lists them")
    fail_on = int(effective["lint"]["failOnPriority"])
    if any(row["level"] <= fail_on for row in rows):
        sys.exit(1)


@common.main.command("hook")
@click.option("--event", default=None, help="The Kiro hook event when stdin does not name it (tests, scripts).")
@click.pass_obj
def hook_command(obj: dict[str, Any], event: str | None) -> None:
    """What Kiro's hooks run: reads the hook JSON from stdin. After a write (PostToolUse) the Groovy file just
    written is checked and the findings printed for the agent; on Stop, with hooks.stopGate on, the turn is refused
    while changed Groovy files have compile errors. Never fails the session."""
    try:
        run_hook(obj, event)
    except Exception as exc:  # a broken .groovyscope.yaml or anything else: logged, and the session goes on
        common.LOG.warning("hook failed: %s", exc, exc_info=True)
        common.LOG.warning(f"groovyscope: hook skipped ({exc})")


def run_hook(obj: dict[str, Any], event: str | None) -> None:
    """The body of ``groovyscope hook`` (see hook_command)."""
    payload = hooks.parse_payload(sys.stdin.read())
    name = hooks.event_name(payload, event)
    if name == "sessionStart":
        session_baseline(obj)
        return
    if name not in {"postToolUse", "stop"}:
        return
    # Everything that needs no engine comes first: most hook events end here, in milliseconds.
    settings = hooks.settings(load_config(obj["root"], obj["config_path"], dict(os.environ)))
    paths, notes = hooks.split_conflicted(hook_targets(obj["root"], name, payload, settings), obj["root"])
    if notes:
        decision = hooks.conflict_decision(notes) if name == "stop" else None
        click.echo(json.dumps(decision) if decision else "\n".join(notes))
        if name == "stop":
            return
    if not paths:
        return
    # A hook never starts the engine: a cold JVM would hold the agent for its start-up and compile. The LSP
    # session Kiro opens starts the daemon; until then the hook says so and steps aside.
    socket_file = common.daemon_paths(obj["root"])[0]
    if common._PROBE(socket_file) is None:
        click.echo("groovyscope: hook skipped, the engine daemon is not running (it starts with the Kiro session)")
        return
    text = (
        hook_after_write(socket_file, obj["root"], paths, settings)
        if name == "postToolUse"
        else hook_stop_gate(socket_file, obj["root"], paths, settings)
    )
    if text:
        click.echo(text)


def hook_targets(root: Path, name: str, payload: Mapping[str, Any], settings: hooks.HookSettings) -> list[Path]:
    """The files the event is about, or nothing when the event needs no engine: the setting is off, the write
    touched no Groovy file, or no Groovy file changed in the working tree since the session started."""
    if name == "postToolUse":
        return hooks.written_paths(payload, root) if settings["afterWrite"] else []
    if not settings["stopGate"]:
        return []
    changed = hooks.changed_groovy_files(root, changes.default_runner)
    return hooks.changed_since_baseline(changed, session_baseline_file(root))


def session_baseline_file(root: Path) -> Path:
    """Where the session's stop gate baseline of a workspace is kept."""
    environ = dict(os.environ)
    return daemon_lifecycle.cache_dir(environ) / "hooks" / f"{daemon_lifecycle.workspace_key(root)}.baseline.json"


def session_baseline(obj: dict[str, Any]) -> None:
    """With hooks.stopGate on, the Groovy files already changed when the agent starts (stat only, nothing read),
    so the gate holds the agent to its own changes. Never fails the start."""
    try:
        if hooks.settings(load_config(obj["root"], obj["config_path"], dict(os.environ)))["stopGate"]:
            hooks.record_baseline(obj["root"], changes.default_runner, session_baseline_file(obj["root"]))
    except Exception as exc:  # a hook step: logged, the session goes on
        common.LOG.warning("stop gate baseline not recorded: %s", exc)


def hook_after_write(socket_file: Path, root: Path, paths: Sequence[Path], settings: hooks.HookSettings) -> str:
    """The after-write block for the Groovy files a write touched; empty when there is nothing to say."""
    rows = hook_check_rows(socket_file, root, paths, settings["afterWriteInclude"], settings["timeoutSeconds"])
    return "" if rows is None else hooks.render_after_write(rows, paths, root, settings["afterWriteMaxFindings"])


def hook_stop_gate(socket_file: Path, root: Path, changed: Sequence[Path], settings: hooks.HookSettings) -> str:
    """The Stop answer: a block decision as JSON while changed Groovy files have compile errors, else empty."""
    rows = hook_check_rows(socket_file, root, changed, ["compile"], settings["timeoutSeconds"])
    decision = hooks.stop_decision(rows or [], settings["afterWriteMaxFindings"])
    return json.dumps(decision) if decision else ""


def hook_check_rows(
    socket_file: Path, root: Path, paths: Sequence[Path], include: Sequence[str], timeout: int
) -> list[dict[str, Any]] | None:
    """The findings for ``paths`` within ``timeout`` seconds, or None (with one line printed) when the engine did
    not answer in time: a hook must never hold the agent hostage."""
    params: dict[str, Any] = {"root": str(root), "uris": common.to_uris(root, list(paths)), "include": list(include)}
    try:
        result = common._RPC(socket_file, "groovyscope/check", params, float(timeout))
    except (OSError, RuntimeError, ValueError) as exc:
        click.echo(f"groovyscope: hook check skipped ({exc})")
        return None
    diagnostics = result.get("diagnostics", {}) if isinstance(result, dict) else {}
    return common.finding_rows(diagnostics, root)


@common.main.command("review")
@click.argument("paths", nargs=-1, type=click.Path(path_type=Path))
@click.option("--changed", is_flag=True, help="Review what this branch changed against --base, as a merge request.")
@click.option("--base", default=None, help="Base branch for --changed (default: the repository's default branch).")
@click.option("--staged", is_flag=True, help="With --changed: review the index instead of the branch.")
@click.option("--top", default=8, show_default=True, help="Findings shown per group.")
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def review_command(
    obj: dict[str, Any],
    *,
    paths: tuple[Path, ...],
    changed: bool,
    base: str | None,
    staged: bool,
    top: int,
    as_json: bool,
) -> None:
    """Review PATHS (or the whole workspace) against the practices checklist, or with --changed review the
    branch's changed Groovy files as a merge request reviewer: blocking, should fix, by design, impact, verdict."""
    socket_file, effective = common.daemon_socket_for(obj)
    tools = common.workspace_tools(obj, socket_file, effective)
    if changed:
        result = tools["review_changes"](base, staged, top)
    else:
        relative = [common.shown_path(obj["root"] / p, obj["root"]) for p in paths]
        result = tools["review"](relative or None, top)
    if as_json:
        click.echo(compact_json(result))
    else:
        for line in render_review(result):
            click.echo(line)
    if (
        result.get("error")
        or result.get("counts", {}).get("blocking")
        or any(group.get("worst", 9) <= 1 for group in result.get("groups", []))
    ):
        sys.exit(1)


def run_build(command: Sequence[str], cwd: Path, timeout: int) -> tuple[int, str]:
    """Run the project's build (``groovyscope verify``); the exit code and the combined output."""
    try:
        done = process.run_text(command, cwd=cwd, timeout=timeout)
    except FileNotFoundError as exc:
        return (-1, f"cannot run {command[0]}: {exc}")
    except subprocess.TimeoutExpired:
        return (-2, f"the build ran longer than {timeout}s (verify.timeoutSeconds) and was stopped")
    return (done.returncode, (done.stdout or "") + (done.stderr or ""))


_VERIFY_RUNNER: Callable[[Sequence[str], Path, int], tuple[int, str]] = run_build


def report_mtime(label: str) -> float:
    return Path(label).stat().st_mtime


def run_tests(command: Sequence[str], root: Path, timeout: int) -> dict[str, Any]:
    """Run the Gradle command (with the init script when it filters tests) and read the JUnit XML reports this run
    wrote: the exit code, the failed test names Gradle printed, the results and, on a failure without reports,
    the output's tail."""
    with tempfile.TemporaryDirectory(prefix="groovyscope-verify-") as folder:
        script = Path(folder) / "verify.init.gradle"
        script.write_text(verify.INIT_SCRIPT, encoding="utf-8")
        started = time.time() - 1  # file systems with one second timestamps
        code, output = _VERIFY_RUNNER(verify.with_init_script(command, script), root, timeout)
    results = test_results.summarise([root], keep=verify.written_since(started, report_mtime))
    gradle: dict[str, Any] = {"exitCode": code, "failed": verify.failed_tests(output), "results": results}
    if code != 0 and not results["failed"]:
        gradle["tail"] = verify.tail(output)
    return gradle


def build_lines(gradle: Mapping[str, Any], command: Sequence[str], limit: int) -> list[str]:
    """``build: passed`` or ``build: FAILED``, the results with each failure's message and place, and a note when
    the filters matched no test."""
    results = gradle["results"]
    lines = ["build: passed" if gradle["exitCode"] == 0 else f"build: FAILED (exit {gradle['exitCode']})"]
    if results["reports"]:
        lines += test_results.lines(results, limit)
    elif "--tests" in command and gradle["exitCode"] == 0:
        lines.append("  note: no test report was written; the --tests filters may have matched no test")
    if not results["failed"]:
        lines += [f"  failed: {name}" for name in gradle["failed"]]
        lines += [f"  {line}" for line in gradle.get("tail", []) if not gradle["failed"]]
    if results["reports"] and not results["tests"] and "--tests" in command:
        lines.append("  note: the --tests filters matched no test; run the classes instead")
    return lines


def changed_uncovered(root: Path, effective: Mapping[str, Any], base: str | None, *, staged: bool) -> dict[str, Any]:
    """Changed methods the run's JaCoCo report says no test executed."""
    configured = reports.reports_settings(effective)["jacoco"]
    report = reports.jacoco_file(root, configured)
    if report is None:
        return {"error": f"no JaCoCo report at {configured or reports.DEFAULT_JACOCO} after the run"}
    try:
        ranges = changes.ranges_by_path(changes.changed_files(root, base, staged=staged))
        gaps = reports.read_jacoco(report, root)
    except ValueError as exc:
        return {"error": str(exc)}
    uncovered = verify.uncovered_in_change(gaps, ranges)
    return {"report": common.shown_path(report, root), "uncovered": uncovered, "count": len(uncovered)}


def shown_limit(effective: Mapping[str, Any]) -> int:
    return int((effective.get("output") or {}).get("maxResults", config_module.default("output", "maxResults")))


def coverage_lines(coverage: Mapping[str, Any]) -> list[str]:
    if coverage.get("error"):
        return [f"coverage: {coverage['error']}"]
    if not coverage["count"]:
        return ["coverage: the tests ran every changed method"]
    lines = [f"coverage: {coverage['count']} changed method(s) no test executed"]
    return lines + [f"  {g['path']}:{g['line']}: {g['name']}" for g in coverage["uncovered"]]


def coverage_plan(plan_result: Mapping[str, Any]) -> dict[str, Any]:
    """The plan with the JaCoCo report task added to its command, or a note when the build has none."""
    args = list(plan_result.get("commandArgs") or [])
    if not args:
        return dict(plan_result)
    if not plan_result.get("coverageTask"):
        return {**plan_result, "note": "the build does not apply JaCoCo: --coverage has nothing to read"}
    args = verify.with_coverage(args, plan_result["coverageTask"])
    return {**plan_result, "commandArgs": args, "command": verify.quoted(args)}


@common.main.command("verify")
@click.option("--base", default=None, help="Base branch (default: the repository's default branch).")
@click.option("--staged", is_flag=True, help="Verify the index instead of the branch.")
@click.option(
    "--failed",
    is_flag=True,
    help="Run again only the tests that failed in the last JUnit XML reports (the workspace, or --results).",
)
@click.option(
    "--results",
    "results_from",
    multiple=True,
    type=click.Path(path_type=Path),
    help="With --failed: folders or ZIP archives (a CI artifacts.zip) holding TEST-*.xml.",
)
@click.option("--coverage", is_flag=True, help="Also build the JaCoCo report and list changed methods no test ran.")
@click.option("--dry-run", is_flag=True, help="Print the plan and the Gradle command without running it.")
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def verify_command(
    obj: dict[str, Any],
    *,
    base: str | None,
    staged: bool,
    failed: bool,
    results_from: tuple[Path, ...],
    coverage: bool,
    dry_run: bool,
    as_json: bool,
) -> None:
    """Verify the change in one step: review the changed lines, then compile the changed source sets and run the
    test methods the change reaches (through the call graph) with one Gradle command, and read the JUnit XML
    reports the run wrote. Tests of tasks outside `verify.runTasks` (integration tests that need CI credentials)
    are listed, not run. Exit 1 on a failed build or test, or a blocking finding."""
    if results_from and not failed:
        raise click.UsageError("--results goes with --failed")
    if failed:
        verify_failed(obj, results_from, dry_run=dry_run, as_json=as_json)
        return
    socket_file, effective = common.daemon_socket_for(obj)
    tools = common.workspace_tools(obj, socket_file, effective)
    result = tools["review_changes"](base, staged, 8)
    if result.get("error"):
        common.LOG.error(f"groovyscope: {result['error']}")
        sys.exit(1)
    settings = effective.get("verify") or {}
    plan_result = result.get("tests") or {}
    lines = [f"verify against {result.get('base')}: {result.get('verdict')}"]
    lines += triage_lines(result.get("triage") or {})
    if coverage:
        plan_result = coverage_plan(plan_result)
    args = list(plan_result.get("commandArgs") or [])
    lines += verify.test_lines(plan_result)
    gradle: dict[str, Any] | None = None
    uncovered: dict[str, Any] | None = None
    if args and not dry_run:
        gradle = run_tests(
            args, obj["root"], int(settings.get("timeoutSeconds", config_module.default("verify", "timeoutSeconds")))
        )
        lines += build_lines(gradle, args, shown_limit(effective))
        if coverage and plan_result.get("coverageTask"):
            uncovered = changed_uncovered(obj["root"], effective, base, staged=staged)
            lines += coverage_lines(uncovered)
    elif args:
        lines.append("dry run: nothing was built")
    blocking = int((result.get("counts") or {}).get("blocking", 0))
    if as_json:
        keys = ("base", "mode", "verdict", "triage", "counts", "files")
        payload = {**{k: result.get(k) for k in keys}, "tests": plan_result, "build": gradle}
        click.echo(compact_json({**payload, "coverage": uncovered} if coverage else payload))
    else:
        for line in lines:
            click.echo(line)
    if blocking or (gradle is not None and gradle["exitCode"] != 0):
        sys.exit(1)


def verify_failed(obj: dict[str, Any], sources: Sequence[Path], *, dry_run: bool, as_json: bool) -> None:
    """``verify --failed``: the failures of the last reports run again, one ``--tests`` filter each."""
    try:
        effective = load_config(obj["root"], obj["config_path"], dict(os.environ))
    except (ValueError, OSError) as exc:
        common.LOG.error(str(exc))
        sys.exit(1)
    root = obj["root"]
    last = test_results.summarise(test_results.default_sources(root, [str(p.resolve()) for p in sources]))
    wrapper = "./gradlew" if (root / "gradlew").is_file() else "gradle"
    command, unplaced = verify.rerun_command(wrapper, last["failed"])
    lines = [f"{len(last['failed'])} failed test(s) in {last['reports']} report(s)"]
    lines += [f"  not from a Gradle test task, run it with its own build: {name}" for name in unplaced]
    lines += [f"  note: {note}" for note in last["notes"]]
    gradle: dict[str, Any] | None = None
    if command:
        lines.append(f"run: {verify.quoted(command)}")
        if dry_run:
            lines.append("dry run: nothing was built")
        else:
            timeout = int(
                (effective.get("verify") or {}).get("timeoutSeconds", config_module.default("verify", "timeoutSeconds"))
            )
            gradle = run_tests(command, root, timeout)
            lines += build_lines(gradle, command, shown_limit(effective))
    if as_json:
        click.echo(compact_json({"last": last, "command": command, "unplaced": unplaced, "build": gradle}))
    else:
        for line in lines:
            click.echo(line)
    if gradle is not None and gradle["exitCode"] != 0:
        sys.exit(1)


@common.main.command("test-results")
@click.argument("sources", nargs=-1, type=click.Path(path_type=Path))
@click.option("--limit", default=20, show_default=True, type=click.IntRange(min=1), help="Failures shown.")
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def test_results_command(obj: dict[str, Any], *, sources: tuple[Path, ...], limit: int, as_json: bool) -> None:
    """Failing tests from JUnit XML reports (TEST-*.xml at any depth) under SOURCES: folders or ZIP archives such as
    a CI pipeline's artifacts.zip (default: the workspace). Each failure with its message, the first frame in the
    project's code and in the test. Exit 1 when a test failed or no report was found."""
    summary = test_results.summarise(test_results.default_sources(obj["root"], [str(p.resolve()) for p in sources]))
    if as_json:
        click.echo(compact_json(summary))
    else:
        for line in test_results.lines(summary, limit):
            click.echo(line)
    if summary["failed"] or not summary["reports"]:
        sys.exit(1)


def triage_lines(triage: Mapping[str, Any]) -> list[str]:
    """``risks: GS002 78 (12 fixable), CatchException 46 · smells: ... · noise: ...`` and the fix command."""
    lines: list[str] = []
    for tier in ("risks", "smells", "noise"):
        found = triage.get(tier) or {}
        if found.get("count"):
            codes = ", ".join(
                f"{c['code']} {c['count']}" + (f" ({c['fixable']} fixable)" if c["fixable"] else "")
                for c in found["codes"]
            )
            lines.append(f"{tier} ({found['count']}): {codes}")
    fix = triage.get("fix")
    if fix:
        lines.append(f"safe fixes for {fix['count']}: `{fix['command']}`")
    return lines


def render_review(result: dict[str, Any]) -> list[str]:
    """The review as text: verdict first, then the groups a reviewer reads in order."""
    if result.get("error"):
        return [f"groovyscope: {result['error']}"]
    lines: list[str] = [f"ATTENTION: {result['attention']}"] if result.get("attention") else []
    lines += [
        f"  unused {item['kind'].rstrip('s')}: {item['path']}:{item['line']} {item.get('name') or item['code']}"
        for item in (result.get("unused") or {}).get("items", [])
    ]
    if "verdict" in result:
        lines.append(f"verdict: {result['verdict']}")
        shown = ", ".join(f"{f['path']} ({f['status']}, {f['changedLines']} lines)" for f in result.get("files", []))
        lines.append(f"changed: {shown or 'nothing reviewable'} (against {result.get('base')})")
        lines += triage_lines(result.get("triage") or {})
        for name, title in (("blocking", "blocking"), ("shouldFix", "should fix"), ("nits", "nits")):
            items = result.get(name, [])
            if items:
                lines.append(f"{title} ({result['counts'][name]}):")
                lines += [f"  {f['path']}:{f['line']}: [{f.get('code')}] {f.get('message')}" for f in items]
        lines += verify.test_lines(result.get("tests") or {})
        for report in result.get("impact", []):
            lines.append(
                f"impact: {report['symbol']} ({report['kind']}, {report['path']}) risk {report['riskLevel']}, "
                f"callers {report['callers']}, tests {report['tests']}"
            )
        for site in result.get("risky", []):
            lines.append(f"added {site['kind']}: {site['path']}:{site['line']} {site.get('detail') or ''}".rstrip())
        elsewhere = result.get("elsewhere", {})
        if elsewhere.get("count"):
            lines.append(f"pre-existing in these files: {elsewhere['count']} ({elsewhere.get('byCode')})")
        for gap in result.get("uncovered", []):
            lines.append(f"uncovered: {gap['name']} ({gap['path']}:{gap['line']}, {gap['missed']} lines)")
        missing = result.get("missingDependencies")
        if missing:
            lines.append(f"missing dependencies in {missing['files']} file(s): {missing['fix']}")
        if result.get("imported"):
            lines.append(f"imported from the build's reports: {result['imported']}")
        return lines
    summary = result.get("summary", {})
    lines.append(
        f"{summary.get('errors', 0)} error(s), {summary.get('warnings', 0)} warning(s), "
        f"{summary.get('information', 0)} info in {summary.get('files', 0)} file(s)"
    )
    for group in result.get("groups", []):
        lines.append(f"{group['title']} ({group['total']}): {group['check']}")
        lines += [f"  {f['path']}:{f['line']}: [{f.get('code')}] {f.get('message')}" for f in group.get("findings", [])]
    return lines


@common.main.command("coverage")
@click.argument("paths", nargs=-1, type=click.Path(path_type=Path))
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def coverage_command(obj: dict[str, Any], *, paths: tuple[Path, ...], as_json: bool) -> None:
    """Classes and methods no test executed, from the build's JaCoCo report (reports.jacoco), most missed first."""
    try:
        effective = load_config(obj["root"], obj["config_path"], dict(os.environ))
    except (ValueError, OSError) as exc:
        common.LOG.error(str(exc))
        sys.exit(1)
    configured = reports.reports_settings(effective)["jacoco"]
    report = reports.jacoco_file(obj["root"], configured)
    if report is None:
        common.LOG.error(
            f"groovyscope: no JaCoCo report at {configured or reports.DEFAULT_JACOCO}; run the build's tests with "
            "jacocoTestReport first"
        )
        sys.exit(1)
    try:
        gaps = reports.filter_gaps(
            reports.read_jacoco(report, obj["root"]), [common.shown_path(obj["root"] / p, obj["root"]) for p in paths]
        )
        summary = reports.coverage_summary(report)
    except ValueError as exc:
        common.LOG.error(f"groovyscope: {exc}")
        sys.exit(1)
    if as_json:
        click.echo(compact_json({"report": common.shown_path(report, obj["root"]), "coverage": summary, "gaps": gaps}))
        return
    click.echo(f"line coverage {summary['percent']}% ({summary['covered']} of {summary['lines']} lines)")
    for gap in gaps:
        click.echo(
            f"{gap['path']}:{gap['line']}: {gap['status']} {gap['kind']} {gap['name']} ({gap['missed']} lines missed)"
        )
    if not gaps:
        click.echo("no uncovered lines")


@common.main.command("naming")
@click.argument("paths", nargs=-1, type=click.Path(path_type=Path))
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output, with the declaration facts.")
@click.pass_obj
def naming_command(obj: dict[str, Any], *, paths: tuple[Path, ...], as_json: bool) -> None:
    """Naming findings (GS020 to GS027) for PATHS or the workspace; --json adds the facts behind each name."""
    socket_file, effective = common.daemon_socket_for(obj)
    tools = common.workspace_tools(obj, socket_file, effective)
    relative = [common.shown_path(obj["root"] / p, obj["root"]) for p in paths]
    result = tools["check_naming"](relative or None, limit=int(effective["output"]["maxResults"]))
    if as_json:
        click.echo(compact_json(result))
        return
    for finding in result.get("items", []):
        click.echo(
            f"{finding['path']}:{finding['line']}:{finding['col']}: {finding['severity']} "
            f"[{finding['code']}] {finding['message']}"
        )
    click.echo(f"{len(result.get('items', []))} naming finding(s); by code: {result.get('byCode', {})}")


@common.main.command("unused")
@click.argument("paths", nargs=-1, type=click.Path(path_type=Path))
@click.option("--changed", is_flag=True, help="Only the lines this branch changed (against --base).")
@click.option("--base", default=None, help="Base branch for --changed (default: the repository's default branch).")
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def unused_command(
    obj: dict[str, Any], *, paths: tuple[Path, ...], changed: bool, base: str | None, as_json: bool
) -> None:
    """Unused imports, variables, parameters, private methods and fields, and unreachable code in PATHS (the
    workspace when empty); exits 1 when there is any, so a pipeline can gate on it."""
    socket_file, effective = common.daemon_socket_for(obj)
    tools = common.workspace_tools(obj, socket_file, effective)
    relative = [common.shown_path(obj["root"] / p, obj["root"]) for p in paths]
    result = tools["find_unused"](relative or None, changed_only=changed, base=base)
    if as_json:
        click.echo(compact_json(result))
    elif result.get("error"):
        click.echo(f"groovyscope: {result['error']}")
    else:
        found = result["unused"]
        for item in found["items"]:
            fixable = " (fix removes it)" if item.get("fixable") else ""
            where = f"{item['path']}:{item['line']}: unused {item['kind'].rstrip('s')}"
            click.echo(f"{where} [{item['code']}] {item['message']}{fixable}")
        click.echo(
            f"{found['total']} unused" + (f": {unused_module.counted(found['byKind'])}" if found["total"] else "")
        )
        if result.get("note"):
            click.echo(result["note"])
    if result.get("error") or result.get("unused", {}).get("total"):
        sys.exit(1)


@common.main.command("format")
@click.argument("paths", nargs=-1, type=click.Path(path_type=Path))
@click.option("--apply", "apply_changes", is_flag=True, help="Write the formatted files; otherwise only report.")
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def format_command(obj: dict[str, Any], paths: tuple[Path, ...], apply_changes: bool, as_json: bool) -> None:
    """Format Groovy files with the bundled formatter; exits 1 when files would change and --apply is not given."""
    socket_file, _ = common.daemon_socket_for(obj)
    result = common.engine_call(
        socket_file, "groovyscope/format", {"root": str(obj["root"]), "uris": common.to_uris(obj["root"], paths)}
    )
    changed, refused = file_edits(result.get("edits", {}), obj["root"])
    rows = [
        {"path": common.shown_path(row["edit"]["path"], obj["root"]), "lines": row["lines"], "applied": row["applied"]}
        for row in settle(changed, refused, apply=apply_changes)
    ]
    for uri in refused:
        common.LOG.warning(f"groovyscope: refused an edit outside the workspace: {uri}")
    checked = int(result.get("checked", 0))
    if as_json:
        click.echo(compact_json({"files": rows, "checked": checked, "changed": len(rows)}))
    else:
        for row in rows:
            click.echo(f"{row['path']}: {row['lines']} line(s) changed{' (applied)' if row['applied'] else ''}")
        click.echo(f"{len(rows)} of {checked} file(s) {'formatted' if apply_changes else 'need formatting'}")
    if rows and not apply_changes:
        sys.exit(1)


@common.main.command()
@click.argument("paths", nargs=-1, type=click.Path(path_type=Path))
@click.option("--code", "codes", multiple=True, help="Only these codes (GS001, CompileStatic, ...); repeatable.")
@click.option("--apply", "apply_changes", is_flag=True, help="Write the fixes; otherwise only report them.")
@click.option("--changed", "only_changed", is_flag=True, help="Only the lines this branch changed (against --base).")
@click.option("--base", default=None, help="Base branch for --changed (default: the repository's default branch).")
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def fix(
    obj: dict[str, Any],
    paths: tuple[Path, ...],
    *,
    codes: tuple[str, ...],
    apply_changes: bool,
    only_changed: bool,
    base: str | None,
    as_json: bool,
) -> None:
    """Apply groovyscope's safe fixes (practice findings and mechanical lint fixes); exits 1 when fixes remain.
    With --changed, only on the lines this branch changed, so existing code is left as it is."""
    params: dict[str, Any] = {
        "root": str(obj["root"]),
        "uris": common.to_uris(obj["root"], paths),
        "codes": list(codes),
    }
    if only_changed:
        try:
            scope = changes.fix_scope(obj["root"], changes.changed_files(obj["root"], base))
        except ValueError as exc:
            common.LOG.error(f"groovyscope: {exc}")
            sys.exit(1)
        if not scope["uris"]:
            click.echo("no changed Groovy lines to fix")
            return
        params.update(scope)
    socket_file, _ = common.daemon_socket_for(obj)
    result = common.engine_call(socket_file, "groovyscope/fix", params)
    changed, refused = file_edits(result.get("edits", {}), obj["root"])
    settled = settle(changed, refused, apply=apply_changes)
    outcome = fix_outcome(result, refused)
    rows: list[dict[str, Any]] = [
        {
            "path": common.shown_path(row["edit"]["path"], obj["root"]),
            "codes": outcome["codes"].get(row["edit"]["path"], []),
            "fixed": outcome["fixed"].get(row["edit"]["path"], {}),
            "lines": row["lines"],
            "applied": row["applied"],
        }
        for row in settled
    ]
    left = {common.shown_path(path, obj["root"]): counts for path, counts in outcome["left"].items()}
    review = {common.shown_path(path, obj["root"]): counts for path, counts in outcome["needsReview"].items()}
    rejected = {common.shown_path(path, obj["root"]): why for path, why in outcome["rejected"].items()}
    if as_json:
        click.echo(compact_json({"files": rows, "left": left, "needsReview": review, "rejected": rejected}))
    else:
        for row in rows:
            state = " (applied)" if row["applied"] else ""
            summary = ", ".join(f"{k} {v}" for k, v in row["fixed"].items()) or ", ".join(row["codes"])
            click.echo(f"{row['path']}: {summary}, {row['lines']} line(s){state}")
        for path, counts in left.items():
            click.echo(f"{path}: left without a safe fix: {', '.join(f'{k} {v}' for k, v in counts.items())}")
        for path, counts in review.items():
            listed = ", ".join(f"{k} {v}" for k, v in counts.items())
            click.echo(f"{path}: needs review, the fix changes behaviour (name it with --code to apply): {listed}")
        for skipped, why in rejected.items():
            click.echo(f"{skipped}: skipped, {why}")
        click.echo(f"{len(rows)} file(s) {'fixed' if apply_changes else 'with fixes available'}")
    if rows and not apply_changes:
        sys.exit(1)


@common.main.command()
@click.argument("code")
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def explain(obj: dict[str, Any], code: str, as_json: bool) -> None:
    """Explain a diagnostic code (GS001, NullChecker, a CodeNarc rule): why it matters and how to fix it."""
    socket_file, _ = common.daemon_socket_for(obj)
    answer = common.engine_call(socket_file, "groovyscope/explain", {"root": str(obj["root"]), "code": code})
    if not answer:
        common.LOG.error(f"groovyscope: nothing known about {code}")
        sys.exit(1)
    if as_json:
        click.echo(compact_json(answer))
        return
    click.echo(f"{answer['code']}: {answer['title']}")
    click.echo(f"why: {answer['why']}")
    click.echo(f"fix: {answer['fix']}")
    if answer.get("link"):
        click.echo(f"see: {answer['link']}")


@common.main.group()
def baseline() -> None:
    """Record and compare the contract surface of files governed by a runtime profile."""


def contracts_now(obj: dict[str, Any]) -> dict[str, dict[str, str]]:
    socket_file, _ = common.daemon_socket_for(obj)
    files = common.engine_call(socket_file, "groovyscope/contracts", {"root": str(obj["root"])}).get("files", {})
    return {str(path): {str(k): str(v) for k, v in items.items()} for path, items in files.items()}


def baseline_file(root: Path) -> Path:
    return root / common.BASELINE_PATH


@baseline.command("save")
@click.pass_obj
def baseline_save(obj: dict[str, Any]) -> None:
    """Record parameter names and types, output keys, frozen methods, exception texts and test seams."""
    current = contracts_now(obj)
    target = baseline_file(obj["root"])
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps({"files": current}, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    items = sum(len(v) for v in current.values())
    shown = common.shown_path(target, obj["root"])
    click.echo(f"{items} contract item(s) in {len(current)} file(s) recorded in {shown}")
    if gitignore.hidden(target):
        click.echo(f"git ignores .groovyscope/; commit the baseline with `git add -f {shown}`")


@baseline.command("check")
@click.pass_obj
def baseline_check(obj: dict[str, Any]) -> None:
    """Compare the contract surface with the recorded baseline; exit 1 on anything removed or changed."""
    target = baseline_file(obj["root"])
    if not target.is_file():
        click.echo(
            f"groovyscope: no baseline at {common.shown_path(target, obj['root'])}; run `groovyscope baseline save`"
        )
        sys.exit(1)
    recorded = json.loads(target.read_text(encoding="utf-8")).get("files", {})
    drift = profiles.contract_drift(recorded, contracts_now(obj))
    for line in drift:
        click.echo(line)
    click.echo(f"{len(drift)} contract change(s)" if drift else "contract unchanged")
    if drift:
        sys.exit(1)
