"""Which tests a change reaches, and the one Gradle command that compiles what changed and runs them.

The tests come from the impact of each changed declaration (the call graph, test callers included) and from the
changed test files themselves. Each test file's Gradle source set (``src/<set>/groovy`` or ``src/<set>/java``) names
its test task: ``test`` for ``src/test``, otherwise the ``Test`` task the build binds to that source set
(``integrationTest`` for ``src/it`` when ``build.gradle`` says so), else the source set's own name. Everything here is
pure; ``groovyscope verify`` runs the command.
"""

from __future__ import annotations

import re
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any, TypedDict

from groovyscope import config

SOURCE_SET = re.compile(r"(?:^|/)src/(?P<set>[^/]+)/(?P<language>groovy|java)/(?P<rest>.+)$")
BUILD_FILES = ("build.gradle", "build.gradle.kts")
# `task integrationTest(type: Test)`, `tasks.register('integrationTest', Test)`, `tasks.register<Test>("it")`
TEST_TASK = re.compile(
    r"task\s+(?P<a>\w+)\s*\(\s*type\s*:\s*Test\b"
    r"|tasks\.register\s*\(\s*['\"](?P<b>\w+)['\"]\s*,\s*Test\b"
    r"|tasks\.register\s*<\s*Test\s*>\s*\(\s*\"(?P<c>\w+)\""
)
TASK_WINDOW = 1500  # characters of a task's body searched for the source set it runs
MAX_DECLARATIONS = 50  # changed declarations whose impact is asked for, to find the tests a change reaches
TEST_SOURCE_PATH = re.compile(r"(?:^|/)src/(?!main/)[^/]+/(?:groovy|java)/")
TEST_NAME_SUFFIXES = ("Test", "Tests", "Spec", "IT", "ITCase", "IntegrationTest")
DEFAULT_RUN = tuple(config.default("verify", "runTasks"))
DEFAULT_COVERAGE_TASK = str(config.default("verify", "coverageTask"))
# Passed with --init-script when the command filters tests: a filter that matches no test in one project or task of
# the build (a multi-project build runs `test` everywhere) is not a failure; the JUnit XML count says what ran.
INIT_SCRIPT = """\
allprojects {
    tasks.withType(Test).configureEach { filter.failOnNoMatchingTests = false }
}
"""
# `shouldRead()`, `shouldRead(String)[2]`, a Spock iteration `reads [x: 1, #0]`: the method or feature is before these.
REPORT_TASK = re.compile(r"(?:^|[/!])test-results/(?P<task>[^/]+)/[^/]+$")
NAME_SUFFIX = re.compile(r"\(|\s\[|\[")


class TestRef(TypedDict):
    """One test method (or a whole test class when ``method`` is empty) and where it lives."""

    className: str
    method: str
    path: str
    sourceSet: str


def source_set_of(path: str) -> tuple[str, str] | None:
    """``(source set, language)`` of a workspace-relative path, or None outside ``src/<set>/<language>``."""
    found = SOURCE_SET.search(path.replace("\\", "/"))
    return (found["set"], found["language"]) if found else None


def class_of(path: str) -> str | None:
    """The qualified class a source file declares by convention (package from its folders, name from the file)."""
    found = SOURCE_SET.search(path.replace("\\", "/"))
    if not found:
        return None
    rest = found["rest"]
    return rest.rsplit(".", 1)[0].replace("/", ".")


def test_tasks(build_text: str) -> dict[str, str]:
    """Source set to ``Test`` task, from the build script text: a task whose body names ``sourceSets.<set>``."""
    matches = list(TEST_TASK.finditer(build_text))
    tasks: dict[str, str] = {}
    for index, match in enumerate(matches):
        name = match["a"] or match["b"] or match["c"]
        end = matches[index + 1].start() if index + 1 < len(matches) else len(build_text)
        body = build_text[match.end() : min(end, match.end() + TASK_WINDOW)]
        for used in re.finditer(
            r"sourceSets\s*(?:\.\s*(?:named\s*\(\s*['\"])?|\[\s*['\"]|\.getByName\(\s*\")(\w+)", body
        ):
            tasks.setdefault(used.group(1), name)
    return tasks


def read_build_text(root: Path, read: Callable[[Path], str | None]) -> str:
    return "\n".join(text for name in BUILD_FILES if (text := read(root / name)) is not None)


def task_for(source_set: str, tasks: Mapping[str, str]) -> str:
    return "test" if source_set == "test" else tasks.get(source_set, source_set)


def affected_tests(
    reports: Sequence[Mapping[str, Any]],
    changed_paths: Sequence[str],
    is_test: Callable[[str], bool],
    touched: Sequence[Mapping[str, Any]] = (),
) -> list[TestRef]:
    """Test methods that call (directly or through other code) a changed declaration, from the impact reports'
    ``tests`` call sites; the changed methods of a changed test file (``touched``: changed declarations with their
    ``path``, ``symbol`` and ``kind``); and a changed test file with no changed method as a whole class."""
    found: dict[tuple[str, str], TestRef] = {}
    for report in reports:
        for site in [*report.get("tests", []), *report.get("integrationTests", [])]:
            caller = site.get("caller") or {}
            path = str((caller.get("location") or {}).get("path") or "")
            located = source_set_of(path)
            class_name = str(caller.get("container") or class_of(path) or "")
            if not class_name or located is None:
                continue
            method = str(caller.get("name") or "")
            found.setdefault(
                (class_name, method), {"className": class_name, "method": method, "path": path, "sourceSet": located[0]}
            )
    changed_methods = {
        str(t["path"]) for t in touched if t.get("kind") == "method" and is_test(str(t.get("path") or ""))
    }
    for item in touched:
        path = str(item.get("path") or "")
        located = source_set_of(path)
        if item.get("kind") != "method" or located is None or not is_test(path):
            continue
        package = class_of(path) or ""
        prefix = package.rsplit(".", 1)[0] + "." if "." in package else ""
        owner, _, method = str(item["symbol"]).rpartition(".")
        class_name = prefix + owner if owner else package
        found.setdefault(
            (class_name, method), {"className": class_name, "method": method, "path": path, "sourceSet": located[0]}
        )
    for path in changed_paths:
        located = source_set_of(path)
        changed_class = class_of(path)
        if located and changed_class and is_test(path) and path not in changed_methods:
            found.setdefault(
                (changed_class, ""), {"className": changed_class, "method": "", "path": path, "sourceSet": located[0]}
            )
    return sorted(found.values(), key=lambda t: (t["sourceSet"], t["className"], t["method"]))


def compile_tasks(changed_paths: Sequence[str]) -> list[str]:
    """``compileGroovy``, ``compileTestGroovy``, ``compileItJava``... for the source sets and languages changed."""
    tasks: list[str] = []
    for path in changed_paths:
        located = source_set_of(path)
        if located is None:
            continue
        source_set, language = located
        prefix = "" if source_set == "main" else source_set[:1].upper() + source_set[1:]
        task = f"compile{prefix}{language.capitalize()}"
        if task not in tasks:
            tasks.append(task)
    return tasks


def test_filter(test: TestRef) -> str:
    """``Class.method`` for one test method, ``Class`` for a whole class."""
    return f"{test['className']}.{test['method']}" if test.get("method") else test["className"]


def filters_by_task(tests: Sequence[TestRef], tasks: Mapping[str, str], run: Sequence[str]) -> dict[str, list[str]]:
    """``--tests`` filters per test task in RUN: one per test method, or the class when the whole class runs (a class
    filter makes its method filters redundant)."""
    whole = {(task_for(t["sourceSet"], tasks), t["className"]) for t in tests if not t["method"]}
    by_task: dict[str, list[str]] = {}
    for test in tests:
        task = task_for(test["sourceSet"], tasks)
        if task not in run or (test["method"] and (task, test["className"]) in whole):
            continue
        name = test_filter(test)
        if name not in by_task.setdefault(task, []):
            by_task[task].append(name)
    return by_task


def gradle_command(
    wrapper: str, compile_with: Sequence[str], tests: Sequence[TestRef], tasks: Mapping[str, str], run: Sequence[str]
) -> list[str]:
    """One Gradle invocation: the compile tasks, then each test task in RUN with its ``--tests`` filters (methods
    where the change names them, so a run checks the tests that matter instead of the whole class)."""
    command = [wrapper, *compile_with]
    for task, names in filters_by_task(tests, tasks, run).items():
        command.append(task)
        for name in names:
            command += ["--tests", name]
    return command if len(command) > 1 else []


def not_run(tests: Sequence[TestRef], tasks: Mapping[str, str], run: Sequence[str]) -> dict[str, list[str]]:
    """Affected test classes per task that ``verify`` leaves to CI (tasks not in RUN)."""
    left: dict[str, list[str]] = {}
    for test in tests:
        task = task_for(test["sourceSet"], tasks)
        if task not in run and test["className"] not in left.setdefault(task, []):
            left[task].append(test["className"])
    return left


INTEGRATION_NAME_SUFFIXES = ("IT", "ITCase", "IntegrationTest")


def is_integration_path(path: str) -> bool:
    """A file in a test source set other than ``test`` (``src/it``, ``src/integrationTest``) or named like an
    integration test (``*IT``, ``*ITCase``, ``*IntegrationTest``)."""
    located = source_set_of(path)
    stem = path.replace("\\", "/").rsplit("/", 1)[-1].rsplit(".", 1)[0]
    in_set = located is not None and located[0] not in {"main", "test"}
    return in_set or (is_test_path(path) and stem.endswith(INTEGRATION_NAME_SUFFIXES))


def integration_out_of_line(
    impacted: Sequence[tuple[Mapping[str, Any], Mapping[str, Any]]],
    ranges: Mapping[str, Sequence[Sequence[int]]],
) -> list[dict[str, Any]]:
    """GS049: a changed declaration whose signature line the branch touched, called from integration test sources
    the branch did not change. ``impacted`` pairs each changed declaration (``symbol``, ``path``, ``lines``) with
    its impact report (``directCallers`` and ``tests`` call sites); ``ranges`` are the branch's changed lines."""
    found: list[dict[str, Any]] = []
    for declaration, report in impacted:
        first = int((declaration.get("lines") or [0])[0])
        path = str(declaration.get("path"))
        if not any(start <= first <= end for start, end in ranges.get(path, ())):
            continue
        callers = sorted(
            {
                str(((site.get("caller") or {}).get("location") or {}).get("path") or "")
                for site in [
                    *report.get("directCallers", []),
                    *report.get("tests", []),
                    *report.get("integrationTests", []),
                ]
            }
        )
        stale = [c for c in callers if c and is_integration_path(c) and c not in ranges]
        if stale:
            found.append(
                {
                    "path": path,
                    "line": first,
                    "column": 1,
                    "severity": "error",
                    "source": "groovyscope",
                    "code": "GS049",
                    "message": f"{declaration.get('symbol')} changed but the integration tests calling it did not: "
                    f"{', '.join(stale[:5])}; update them in this change",
                }
            )
    return found


def is_test_path(path: str) -> bool:
    """A file in a test source set (any but ``main``) or named like a test class; the engine's rule, in Python."""
    stem = path.replace("\\", "/").rsplit("/", 1)[-1].rsplit(".", 1)[0]
    return bool(TEST_SOURCE_PATH.search(path.replace("\\", "/"))) or stem.endswith(TEST_NAME_SUFFIXES)


def read_text(path: Path) -> str | None:
    try:
        return path.read_text(encoding="utf-8")
    except OSError:
        return None


def plan(
    root: Path,
    reports: Sequence[Mapping[str, Any]],
    changed_paths: Sequence[str],
    settings: Mapping[str, Any],
    *,
    limit: int,
    read: Callable[[Path], str | None] = read_text,
    touched: Sequence[Mapping[str, Any]] = (),
) -> dict[str, Any]:
    """The tests the change reaches and how to run them: ``items`` (class, method, path, source set), the Gradle
    ``command`` that compiles the changed source sets and runs the affected tests of the tasks in
    ``verify.runTasks`` (``commandArgs`` as a list for ``groovyscope verify``), and ``inCi``: affected tests of other
    tasks (integration tests that need the pipeline's credentials), per task."""
    tests = affected_tests(reports, changed_paths, is_test_path, touched)
    build = read_build_text(root, read)
    tasks = test_tasks(build)
    run = [str(t) for t in settings.get("runTasks") or DEFAULT_RUN]
    wrapper = "./gradlew" if read(root / "gradlew") is not None else "gradle"
    command = gradle_command(wrapper, compile_tasks(changed_paths), tests, tasks, run) if build else []
    result: dict[str, Any] = {"count": len(tests), "items": list(tests[: max(1, limit)])}
    if "jacoco" in build:
        result["coverageTask"] = str(settings.get("coverageTask") or DEFAULT_COVERAGE_TASK)
    if command:
        result["command"] = quoted(command)
        result["commandArgs"] = command
    elif not build:
        result["note"] = "no build.gradle here: run the listed tests with the project's own build"
    left = not_run(tests, tasks, run)
    if left:
        result["inCi"] = left
    return result


def quoted(command: Sequence[str]) -> str:
    return " ".join(part if re.fullmatch(r"[\w./:=-]+", part) else f"'{part}'" for part in command)


FAILED_TEST = re.compile(r"^(?P<test>\S.*?) FAILED$", re.MULTILINE)
OUTPUT_TAIL = 30


def failed_tests(output: str) -> list[str]:
    """``StoreIT > shouldRead() FAILED`` lines Gradle prints, without the task failure line."""
    return [m["test"] for m in FAILED_TEST.finditer(output) if " > " in m["test"]]


def tail(output: str, lines: int = OUTPUT_TAIL) -> list[str]:
    return [line for line in output.splitlines() if line.strip()][-lines:]


def test_lines(plan_result: Mapping[str, Any]) -> list[str]:
    """``tests the change reaches (4): StoreIT.shouldRead, ...``, the command and what is left to CI."""
    lines: list[str] = []
    count = int(plan_result.get("count") or 0)
    if count:
        names = ", ".join(
            f"{t['className'].rsplit('.', 1)[-1]}.{t['method']}" if t["method"] else t["className"].rsplit(".", 1)[-1]
            for t in plan_result.get("items", [])
        )
        more = f" +{count - len(plan_result.get('items', []))}" if count > len(plan_result.get("items", [])) else ""
        lines.append(f"tests the change reaches ({count}): {names}{more}")
    if plan_result.get("command"):
        lines.append(f"run: {plan_result['command']}")
    for task, classes in (plan_result.get("inCi") or {}).items():
        lines.append(f"left to CI ({task}): {', '.join(c.rsplit('.', 1)[-1] for c in classes)}")
    if plan_result.get("note"):
        lines.append(f"note: {plan_result['note']}")
    return lines


def with_init_script(command: Sequence[str], script: Path) -> list[str]:
    """The command with ``--init-script`` when it filters tests (see :data:`INIT_SCRIPT`)."""
    if "--tests" not in command:
        return list(command)
    return [command[0], "--init-script", str(script), *command[1:]]


def with_coverage(command: Sequence[str], task: str | None) -> list[str]:
    """The command with the coverage report task last, once; unchanged when the build has none."""
    if not command or not task or task in command:
        return list(command)
    return [*command, task]


def method_name(name: str) -> str:
    """A JUnit XML test name as a Gradle filter method: without ``()``, parameters or an iteration suffix."""
    return NAME_SUFFIX.split(name, maxsplit=1)[0].strip()


def task_of_report(label: str) -> str | None:
    """The Gradle test task that wrote a report: ``<module>/build/test-results/<task>/TEST-*.xml`` (also inside an
    archive, ``artifacts.zip!...``); None for reports of other tools (Maven surefire)."""
    found = REPORT_TASK.search(label.replace("\\", "/"))
    return found["task"] if found else None


def rerun_command(wrapper: str, failures: Sequence[Mapping[str, Any]]) -> tuple[list[str], list[str]]:
    """The Gradle command that runs only the failed tests, each test task with its ``--tests`` filters, and the
    failures it cannot place (reports not written by a Gradle test task)."""
    by_task: dict[str, list[str]] = {}
    unplaced: list[str] = []
    for failure in failures:
        task = task_of_report(str(failure.get("report") or ""))
        method = method_name(str(failure.get("name") or ""))
        name = f"{failure['className']}.{method}" if method else str(failure["className"])
        if task is None:
            unplaced.append(name)
        elif name not in by_task.setdefault(task, []):
            by_task[task].append(name)
    command = [wrapper]
    for task, names in by_task.items():
        command.append(task)
        for name in names:
            command += ["--tests", name]
    return (command if len(command) > 1 else [], unplaced)


def written_since(started: float, stat: Callable[[str], float]) -> Callable[[str], bool]:
    """A ``keep`` for :func:`groovyscope.test_results.summarise`: reports written by this run, not older ones."""

    def keep(label: str) -> bool:
        try:
            return stat(label) >= started
        except OSError:
            return False

    return keep


def uncovered_in_change(
    gaps: Sequence[Mapping[str, Any]], ranges: Mapping[str, Sequence[Sequence[int]]]
) -> list[dict[str, Any]]:
    """Methods JaCoCo saw no test execute whose first line the change touched."""
    return [
        dict(g)
        for g in gaps
        if g.get("kind") == "method"
        and g.get("status") == "uncovered"
        and any(start <= int(g.get("line") or 0) <= end for start, end in ranges.get(str(g.get("path")), ()))
    ]
