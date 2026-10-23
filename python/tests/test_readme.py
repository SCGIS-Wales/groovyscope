"""The README stays true to the code: every command and option it shows exists, every MCP tool is listed with
the parameters it really takes, and every configuration key, relative link and repository path it names exists.
The other documents are held to the last two."""

from __future__ import annotations

import asyncio
import json
import re
import shlex
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import click
import pytest

from groovyscope import mcp_server
from groovyscope.cli import main

REPO = Path(__file__).resolve().parents[2]
README = REPO / "README.md"
pytestmark = pytest.mark.skipif(not README.is_file(), reason="repository checkout required")


def text() -> str:
    return README.read_text(encoding="utf-8")


def walk(command: click.Command, path: tuple[str, ...] = ()) -> Iterator[tuple[tuple[str, ...], click.Command]]:
    yield path, command
    if isinstance(command, click.Group):
        for name, sub in command.commands.items():
            yield from walk(sub, (*path, name))


def options(command: click.Command) -> set[str]:
    names = {"--help", "-h"}
    for param in command.params:
        names.update(getattr(param, "opts", []))
        names.update(getattr(param, "secondary_opts", []))
    return names


def invocations() -> list[str]:
    body = text()
    return sorted(
        set(re.findall(r"`(groovyscope [^`]+)`", body) + re.findall(r"^\s*(groovyscope [^\n#]+)", body, re.M))
    )


def problems_of(raw: str) -> list[str]:
    words = shlex.split(raw.replace("<", "").replace(">", ""))[1:]
    command: click.Command = main
    path: list[str] = []
    while words and words[0].startswith("-"):
        option = words.pop(0).split("=")[0]
        if option not in options(main):
            return [f"unknown global option {option}"]
        if words and not words[0].startswith("-"):
            words.pop(0)
    while words and isinstance(command, click.Group) and words[0] in command.commands:
        path.append(words[0])
        command = command.commands[words.pop(0)]
    if isinstance(command, click.Group) and words and not words[0].startswith("-") and not words[0].isupper():
        return [f"unknown command {words[0]!r} under {' '.join(path) or 'groovyscope'}"]
    return [
        f"unknown option {w} for {' '.join(path)}"
        for w in words
        if w.startswith("-") and w.split("=")[0] not in options(command)
    ]


def test_every_command_and_option_the_readme_shows_exists() -> None:
    found = {raw: problems_of(raw) for raw in invocations()}
    assert found and {raw: why for raw, why in found.items() if why} == {}


def test_every_top_level_command_is_in_the_readme() -> None:
    body = text()
    missing = [name for name, cmd in main.commands.items() if not cmd.hidden and f"`groovyscope {name}" not in body]
    assert missing == []


def test_the_mcp_tool_table_matches_the_registered_tools() -> None:
    body = text()
    section = body[body.index("## MCP tools") : body.index("## Configuration")]
    listed = {
        name: sorted(p.strip().rstrip("?") for p in params.split(",") if p.strip())
        for name, params in re.findall(r"^\| `([a-z_]+)\(([^)]*)\)`", section, re.M)
    }
    server = mcp_server.build_server(REPO, lambda *_a, **_k: {}, {}, {})
    tools: dict[str, Any] = {t.name: t for t in asyncio.run(server.list_tools())}
    registered = {name: sorted(tool.input_schema.get("properties", {})) for name, tool in tools.items()}
    assert listed == registered


def test_configuration_keys_links_and_paths_exist() -> None:
    body = text()
    schema = json.loads((REPO / "python" / "src" / "groovyscope" / "config-schema.json").read_text(encoding="utf-8"))

    def known(path: list[str]) -> bool:
        node = schema
        for part in path:
            node = node.get("properties", {}).get(part)
            if node is None:
                return False
        return True

    keys = {
        k for k in re.findall(r"`([a-z]+\.[a-zA-Z]+(?:\.[a-zA-Z]+)?)`", body) if k.split(".")[0] in schema["properties"]
    }
    assert sorted(k for k in keys if not known(k.split("."))) == []
    links = [link for link in re.findall(r"\]\(([^)#\s]+)", body) if not link.startswith("http")]
    assert [link for link in links if not (REPO / link).exists()] == []
    assert missing_paths(body) == []


# Plans and dated research describe the repository as it was or will be; examples name files a user would create.
HISTORICAL = frozenset({"roadmap.md", "improvement-plan.md", "edit-intelligence-plan.md"})
EXAMPLES = frozenset({"docs/classes.drawio"})


def missing_paths(body: str) -> list[str]:
    """Repository paths quoted in ``body`` that do not exist."""
    paths = set(re.findall(r"`((?:docs|config|kiro|scripts|python|engine|\.github)/[^`\s*<]+)`", body))
    return sorted(p for p in paths - EXAMPLES if not (REPO / p).exists())


def documents() -> list[Path]:
    return [
        *(doc for doc in sorted((REPO / "docs").glob("*.md")) if doc.name not in HISTORICAL),
        REPO / "CONTRIBUTING.md",
        REPO / "AGENTS.md",
    ]


@pytest.mark.parametrize("document", documents(), ids=lambda doc: doc.name)
def test_every_document_names_paths_and_links_that_exist(document: Path) -> None:
    body = document.read_text(encoding="utf-8")
    assert missing_paths(body) == []
    links = [link for link in re.findall(r"\]\(([^)#\s]+)", body) if not re.match(r"[a-z]+:", link)]
    assert [link for link in links if not (document.parent / link).exists()] == []
