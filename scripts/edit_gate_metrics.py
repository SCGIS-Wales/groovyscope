#!/usr/bin/env python3
"""Measure what the edit gate's thresholds are calibrated on: comments per method, Javadoc lengths, and the size of
test classes against their subjects, for every Groovy file under a folder.

Usage: uv run --project python python scripts/edit_gate_metrics.py FOLDER [--json]

Method ranges come from the engine (``groovyscope/parse`` with declarations, through the workspace daemon started
for FOLDER); comments are found by a small lexer that knows line and block comments and the four Groovy string
quotes. Prints a Markdown summary, or the raw numbers with ``--json``.
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from collections.abc import Callable, Iterable, Sequence
from pathlib import Path
from typing import Any, TypedDict

BATCH = 16
TEST_SUFFIXES = ("Spec", "Test", "Tests", "IT")
Parser = Callable[[str, Sequence[str]], list[dict[str, Any]]]


class LineKinds(TypedDict):
    comment: set[int]  # 1-based lines holding only comment text
    code: set[int]  # 1-based lines holding code (a trailing comment counts as code)
    javadoc: list[tuple[int, int]]  # 1-based first and last line of each /** */ block


def line_kinds(text: str) -> LineKinds:
    """Which lines are comment, which code, and where the Javadoc blocks are."""
    comment: set[int] = set()
    code: set[int] = set()
    javadoc: list[tuple[int, int]] = []
    line, index, length = 1, 0, len(text)
    while index < length:
        char = text[index]
        if char == "\n":
            line += 1
            index += 1
        elif text.startswith("//", index):
            end = text.find("\n", index)
            end = length if end < 0 else end
            comment.add(line)
            index = end
        elif text.startswith("/*", index):
            end = text.find("*/", index + 2)
            end = length if end < 0 else end + 2
            first = line
            line += text.count("\n", index, end)
            comment.update(range(first, line + 1))
            if text.startswith("/**", index):
                javadoc.append((first, line))
            index = end
        elif char in "'\"":
            quote = text[index : index + 3] if text.startswith(char * 3, index) else char
            end = text.find(quote, index + len(quote))
            while end > 0 and text[end - 1] == "\\" and len(quote) == 1:
                end = text.find(quote, end + 1)
            end = length if end < 0 else end + len(quote)
            code.add(line)
            line += text.count("\n", index, end)
            code.add(line)
            index = end
        else:
            if not char.isspace():
                code.add(line)
            index += 1
    return {"comment": comment - code, "code": code, "javadoc": javadoc}


def percentile(values: Sequence[float], share: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    return ordered[min(len(ordered) - 1, int(share * len(ordered)))]


def summary(values: Sequence[float]) -> dict[str, float]:
    if not values:
        return {"count": 0, "median": 0.0, "p90": 0.0, "max": 0.0}
    return {
        "count": len(values),
        "median": float(statistics.median(values)),
        "p90": percentile(values, 0.9),
        "max": float(max(values)),
    }


def method_rows(path: str, text: str, declarations: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    """Per method: code lines, comment lines in the body, the longest comment run, and the Javadoc above it."""
    kinds = line_kinds(text)
    blocks = {last: first for first, last in kinds["javadoc"]}
    rows = []
    for item in declarations:
        if item.get("kind") not in {"method", "constructor"}:
            continue
        start, end = int(item["line"]), int(item["endLine"])
        body = range(start, end + 1)
        comments = [n for n in body if n in kinds["comment"]]
        longest = run = 0
        for n in body:
            run = run + 1 if n in kinds["comment"] else 0
            longest = max(longest, run)
        doc_end = start - 1
        while (
            doc_end > 0
            and doc_end not in blocks
            and doc_end in kinds["code"]
            and text.splitlines()[doc_end - 1].strip().startswith("@")
        ):
            doc_end -= 1
        javadoc = javadoc_text_lines(text, blocks[doc_end], doc_end) if doc_end in blocks else 0
        rows.append(
            {
                "path": path,
                "name": item["name"],
                "code": sum(1 for n in body if n in kinds["code"]),
                "comments": len(comments),
                "longestRun": longest,
                "javadoc": javadoc,
            }
        )
    return rows


def javadoc_text_lines(text: str, first: int, last: int) -> int:
    """Lines of a /** */ block that hold text, not counting delimiter-only lines."""
    lines = text.splitlines()[first - 1 : last]
    return sum(1 for line in lines if line.strip().strip("/*").strip())


def subject_of(path: Path) -> str | None:
    """The class a test class tests, by name (``FooSpec`` tests ``Foo``); None for a main class."""
    stem = path.stem
    for suffix in TEST_SUFFIXES:
        if stem.endswith(suffix) and len(stem) > len(suffix):
            return stem[: -len(suffix)]
    return None


def measure(folder: Path, parse: Parser) -> dict[str, Any]:
    files = sorted(folder.rglob("*.groovy"))
    texts = {str(f.relative_to(folder)): f.read_text(encoding="utf-8", errors="replace") for f in files}
    rows: list[dict[str, Any]] = []
    names = list(texts)
    for start in range(0, len(names), BATCH):
        chunk = names[start : start + BATCH]
        for name, result in zip(chunk, parse("Metrics.groovy", [texts[n] for n in chunk]), strict=True):
            rows += method_rows(name, texts[name], result.get("declarations") or [])
    bodies = [r for r in rows if r["code"] >= 5]  # noqa: PLR2004 - methods of five code lines or more
    density = [r["comments"] / r["code"] for r in bodies]
    main_lines = {Path(n).stem: len(t.splitlines()) for n, t in texts.items() if subject_of(Path(n)) is None}
    tests: list[dict[str, float]] = []
    for name, text in texts.items():
        subject = subject_of(Path(name))
        if subject and subject in main_lines:
            methods = sum(1 for r in rows if r["path"] == name)
            tests.append({"ratio": len(text.splitlines()) / max(1, main_lines[subject]), "methods": float(methods)})
    return {
        "files": len(files),
        "methods": len(rows),
        "commentLinesPerMethod": summary([r["comments"] for r in rows]),
        "commentDensityOfMethodsOver5Lines": summary(density),
        "methodsWithDensityOver025": sum(1 for d in density if d > 0.25),  # noqa: PLR2004 - the plan's default
        "longestCommentRun": summary([r["longestRun"] for r in rows]),
        "methodsWithRunOver2": sum(1 for r in rows if r["longestRun"] > 2),  # noqa: PLR2004 - the plan's default
        "javadocLines": summary([r["javadoc"] for r in rows if r["javadoc"]]),
        "javadocOver3": sum(1 for r in rows if r["javadoc"] > 3),  # noqa: PLR2004 - the plan's default
        "testClasses": len(tests),
        "testToCodeRatio": summary([t["ratio"] for t in tests]),
        "testMethodsPerClass": summary([t["methods"] for t in tests]),
    }


def markdown(numbers: dict[str, Any]) -> str:
    lines = [f"Files: {numbers['files']}, methods and constructors: {numbers['methods']}.", ""]
    lines += ["| Measure | Count | Median | P90 | Max |", "|---|---|---|---|---|"]
    for key, value in numbers.items():
        if isinstance(value, dict):
            lines.append(
                f"| {key} | {value['count']} | {value['median']:.2f} | {value['p90']:.2f} | {value['max']:.2f} |"
            )
    lines.append("")
    lines += [
        f"- {key}: {value}"
        for key, value in numbers.items()
        if isinstance(value, int) and key not in {"files", "methods"}
    ]
    return "\n".join(lines)


def daemon_parser(folder: Path) -> Parser:  # pragma: no cover - needs the engine
    from groovyscope import rpc  # noqa: PLC0415 - the package is only needed when the engine is used
    from groovyscope.cli import common  # noqa: PLC0415

    obj = {"config_path": None, "root": folder.resolve(), "log_level": "WARNING", "log_file": None}
    socket_file, _ = common.daemon_socket_for(obj)

    def parse(name: str, texts: Sequence[str]) -> list[dict[str, Any]]:
        params = {"root": str(folder.resolve()), "name": name, "texts": list(texts), "declarations": True}
        answer = rpc.request(socket_file, "groovyscope/parse", params, 600.0)
        return list(answer.get("results", []))

    return parse


def main(argv: Sequence[str] | None = None) -> int:  # pragma: no cover - a command-line wrapper
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0] if __doc__ else None)
    parser.add_argument("folder", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    numbers = measure(args.folder, daemon_parser(args.folder))
    print(json.dumps(numbers, indent=2) if args.json else markdown(numbers))
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
