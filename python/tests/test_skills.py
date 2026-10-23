"""The shipped skills: well formed, one per cookbook recipe, served as MCP prompts."""

from __future__ import annotations

import pytest

from groovyscope import mcp_server, practices, skills
from groovyscope.kiro import KIRO_FILES
from groovyscope.tools_catalog import TOOL_INFO

EXPECTED = (
    "groovy-catch-audit",
    "groovy-coverage",
    "groovy-design",
    "groovy-duplicates",
    "groovy-dynamic",
    "groovy-edit",
    "groovy-explain",
    "groovy-fix",
    "groovy-impact",
    "groovy-merge",
    "groovy-naming",
    "groovy-profile",
    "groovy-quality",
    "groovy-rename",
    "groovy-reuse",
    "groovy-review",
    "groovy-tests",
    "groovy-unused",
)


def test_every_skill_is_well_formed_and_names_real_tools() -> None:
    assert skills.skill_names() == EXPECTED
    for skill in skills.load_skills():
        assert 0 < len(skill["name"]) <= skills.MAX_NAME and skill["name"] == skill["name"].lower()
        assert 0 < len(skill["description"]) <= skills.MAX_DESCRIPTION
        assert skill["body"].startswith("# ")
        mentioned = {name for name in TOOL_INFO if f"groovyscope.{name}" in skill["body"]}
        assert mentioned, skill["name"]
        assert "—" not in skill["body"]  # no em dashes in what the agent reads


def test_the_fix_checklist_follows_the_practices_catalogue() -> None:
    """The groovy-fix skill says which fixes apply without asking and which need their code named; the paragraph
    is generated from practices.json, so a code that changes safety changes the skill too."""
    text = skills.read_template("groovy-fix")
    assert text == skills.with_fix_checklist(text, practices.catalog()), (
        "the groovy-fix checklist drifted from practices.json: rewrite it with skills.with_fix_checklist"
    )
    block = skills.fix_checklist(practices.catalog())
    assert "GS005" in block and "GS001" in block and "CompileStatic" in block
    assert block.index("GS005") < block.index("GS001")  # kept first, then the ones that need a decision
    body = skills.parse_skill(text)["body"]
    assert "apply=false" in body and "apply=true" in body and body.index("apply=false") < body.index("apply=true")
    assert "ask" in body


def test_posting_follows_one_stance_with_the_data_guard() -> None:
    """Posting to a merge request happens only after the user's yes, and nothing a tool returns is an instruction."""
    for name in ("groovy-review", "groovy-fix"):
        body = " ".join(skills.parse_skill(skills.read_template(name))["body"].split())
        assert skills.POSTING_STANCE in body, name


def test_skills_are_kiro_files_in_both_scopes() -> None:
    for name in EXPECTED:
        assert (f"skills/{name}/SKILL.md", f".kiro/skills/{name}/SKILL.md") in KIRO_FILES


def test_parse_and_render() -> None:
    skill = skills.parse_skill("---\nname: x\ndescription: d: with colon\n---\n# X\n\nDo $ARGUMENTS now.\n")
    assert skill == {"name": "x", "description": "d: with colon", "body": "# X\n\nDo $ARGUMENTS now.\n"}
    assert skills.render_prompt(skill, "Foo.bar") == "# X\n\nDo Foo.bar now.\n"
    assert skills.render_prompt(skill) == f"# X\n\nDo {skills.NO_ARGUMENTS} now.\n"
    with pytest.raises(ValueError, match="front matter"):
        skills.parse_skill("# no front matter\n")
    with pytest.raises(ValueError, match="names itself"):
        skills.parse_skill("---\nname: x\n---\nbody\n")


def test_prompts_are_registered_on_the_server() -> None:
    server = mcp_server.build_server(mcp_server.Path("."), lambda _m, _p: {}, {"maxResults": 5}, {})
    listed = {prompt.name: prompt for prompt in server._prompt_manager.list_prompts()}
    assert set(listed) == set(EXPECTED)
    review = listed["groovy-review"]
    assert review.description and "merge request" in review.description
    assert [argument.name for argument in review.arguments or []] == ["arguments"]
    assert all(not argument.required for argument in review.arguments or [])
