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
DEFAULT_RUN = ("test",)


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
    reports: Sequence[Mapping[str, Any]], changed_paths: Sequence[str], is_test: Callable[[str], bool]
) -> list[TestRef]:
    """Test methods that call (directly or through other code) a changed declaration, from the impact reports'
    ``tests`` call sites, plus every changed test file as a whole class."""
    found: dict[tuple[str, str], TestRef] = {}
    for report in reports:
        for site in report.get("tests", []):
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
    for path in changed_paths:
        located = source_set_of(path)
        changed_class = class_of(path)
        if located and changed_class and is_test(path):
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


def gradle_command(
    wrapper: str, compile_with: Sequence[str], tests: Sequence[TestRef], tasks: Mapping[str, str], run: Sequence[str]
) -> list[str]:
    """One Gradle invocation: the compile tasks, then each test task in RUN with a ``--tests`` filter per class."""
    by_task: dict[str, list[str]] = {}
    for test in tests:
        task = task_for(test["sourceSet"], tasks)
        if task in run and test["className"] not in by_task.setdefault(task, []):
            by_task[task].append(test["className"])
    command = [wrapper, *compile_with]
    for task, classes in by_task.items():
        command.append(task)
        for class_name in classes:
            command += ["--tests", class_name]
    return command if len(command) > 1 else []


def not_run(tests: Sequence[TestRef], tasks: Mapping[str, str], run: Sequence[str]) -> dict[str, list[str]]:
    """Affected test classes per task that ``verify`` leaves to CI (tasks not in RUN)."""
    left: dict[str, list[str]] = {}
    for test in tests:
        task = task_for(test["sourceSet"], tasks)
        if task not in run and test["className"] not in left.setdefault(task, []):
            left[task].append(test["className"])
    return left


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
) -> dict[str, Any]:
    """The tests the change reaches and how to run them: ``items`` (class, method, path, source set), the Gradle
    ``command`` that compiles the changed source sets and runs the affected tests of the tasks in
    ``verify.runTasks`` (``commandArgs`` as a list for ``groovyscope verify``), and ``inCi``: affected tests of other
    tasks (integration tests that need the pipeline's credentials), per task."""
    tests = affected_tests(reports, changed_paths, is_test_path)
    build = read_build_text(root, read)
    tasks = test_tasks(build)
    run = [str(t) for t in settings.get("runTasks") or DEFAULT_RUN]
    wrapper = "./gradlew" if read(root / "gradlew") is not None else "gradle"
    command = gradle_command(wrapper, compile_tasks(changed_paths), tests, tasks, run) if build else []
    result: dict[str, Any] = {"count": len(tests), "items": list(tests[: max(1, limit)])}
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
