"""Catalogue lookups, review grouping, and the short and SARIF output formats."""

from __future__ import annotations

from groovyscope import practices

ROWS = [
    {
        "path": "src/A.groovy",
        "line": 5,
        "column": 3,
        "severity": "warning",
        "level": 2,
        "source": "groovyscope",
        "code": "GS001",
        "message": "m",
    },
    {
        "path": "src/A.groovy",
        "line": 2,
        "column": 1,
        "severity": "error",
        "level": 1,
        "source": "groovyc",
        "code": "syntax",
        "message": "e",
    },
    {
        "path": "src/B.groovy",
        "line": 1,
        "column": 1,
        "severity": "info",
        "level": 3,
        "source": "codenarc",
        "code": "",
        "message": "i",
    },
]


def test_lookups() -> None:
    assert practices.entry("GS012")["group"] == "security"
    assert practices.entry("Nope") is None
    assert practices.link(
        "GS001",
    ).endswith("practices.md#23-numeric-pitfalls")
    assert practices.link("Nope") is None
    assert practices.group_of("NullChecker") == "boundaries"
    assert practices.group_of("SomeCodeNarcRule") == "style"
    assert practices.level({"severity": "error"}) == 1 and practices.level({"level": 4}) == 4


def test_review_orders_groups_by_worst_then_size() -> None:
    groups = practices.review(ROWS, top=5)
    assert [g["group"] for g in groups] == ["style", "numbers"]
    assert groups[0]["counts"] == {"error": 1, "info": 1} and groups[0]["findings"][0]["code"] == "syntax"


def test_short_and_sarif() -> None:
    assert practices.short_lines(ROWS[:1]) == ["src/A.groovy:5:3: warning: m [GS001]"]
    log = practices.sarif(ROWS)
    assert log["version"] == "2.1.0" and log["$schema"].endswith("sarif-2.1.0.json")
    run = log["runs"][0]
    rules = {r["id"]: r for r in run["tool"]["driver"]["rules"]}
    assert rules["GS001"]["helpUri"].endswith("#23-numeric-pitfalls") and rules["GS001"]["shortDescription"]["text"]
    assert set(rules) == {"GS001", "syntax", "codenarc"}
    assert [r["level"] for r in run["results"]] == ["warning", "error", "note"]
    region = run["results"][0]["locations"][0]["physicalLocation"]["region"]
    assert region == {"startLine": 5, "startColumn": 3}
