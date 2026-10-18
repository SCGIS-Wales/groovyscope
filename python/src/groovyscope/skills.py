"""The groovyscope skills: Kiro ``SKILL.md`` files shipped as templates, and the same recipes as MCP prompts.

A skill is one recipe from the prompts cookbook (``docs/prompts.md``): front matter with ``name`` and
``description`` (what Kiro loads at start), and a body the agent reads only when the skill is invoked as a slash
command. The MCP server serves each skill as a prompt too, so a client without Kiro's skills still sees them.
"""

from __future__ import annotations

from importlib import resources
from typing import TypedDict

SKILLS_DIR = "_templates/kiro/skills"
SKILL_FILE = "SKILL.md"
ARGUMENTS_PLACEHOLDER = "$ARGUMENTS"
NO_ARGUMENTS = "the target named in the request"
MAX_NAME = 64
MAX_DESCRIPTION = 1024


class Skill(TypedDict):
    """One skill: its slash-command name, the one-line description Kiro shows, and the body."""

    name: str
    description: str
    body: str


def parse_skill(text: str) -> Skill:
    """``name`` and ``description`` from the front matter, the Markdown after it as the body."""
    if not text.startswith("---\n"):
        msg = "a skill starts with YAML front matter"
        raise ValueError(msg)
    end = text.index("\n---\n", 4)
    fields: dict[str, str] = {}
    for line in text[4:end].splitlines():
        key, separator, value = line.partition(":")
        if separator:
            fields[key.strip()] = value.strip()
    name = fields.get("name", "")
    description = fields.get("description", "")
    if not name or not description:
        msg = "a skill names itself and says what it is for"
        raise ValueError(msg)
    return {"name": name, "description": description, "body": text[end + len("\n---\n") :].strip("\n") + "\n"}


def skill_names() -> tuple[str, ...]:
    """The shipped skills, in name order."""
    folder = resources.files("groovyscope").joinpath(SKILLS_DIR)
    return tuple(sorted(entry.name for entry in folder.iterdir() if entry.joinpath(SKILL_FILE).is_file()))


def load_skills() -> list[Skill]:
    """Every shipped skill, parsed."""
    folder = resources.files("groovyscope").joinpath(SKILLS_DIR)
    return [parse_skill(folder.joinpath(name, SKILL_FILE).read_text(encoding="utf-8")) for name in skill_names()]


def render_prompt(skill: Skill, arguments: str = "") -> str:
    """The skill body as a prompt, with ``$ARGUMENTS`` replaced by what the user passed (or a placeholder)."""
    return skill["body"].replace(ARGUMENTS_PLACEHOLDER, arguments.strip() or NO_ARGUMENTS)
