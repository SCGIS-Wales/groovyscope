"""The practice catalogue: well formed, linked to real headings in docs/practices.md, every GS code present."""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

PACKAGE = Path(__file__).resolve().parents[1] / "src" / "groovyscope"
CATALOG = PACKAGE / "_templates" / "config" / "practices" / "practices.json"
DOCS = Path(__file__).resolve().parents[2] / "docs" / "practices.md"


def slug(heading: str) -> str:
    """GitHub's anchor for a Markdown heading."""
    text = re.sub(r"[^\w\- ]", "", heading.strip().lower())
    return text.replace(" ", "-")


def test_catalog_is_complete_and_consistent() -> None:
    data = json.loads(CATALOG.read_text(encoding="utf-8"))
    groups = {g["id"] for g in data["groups"]}
    codes = [e["code"] for e in data["entries"]]
    assert len(codes) == len(set(codes))
    assert {f"GS{n:03d}" for n in range(1, 13)} <= set(codes)
    for entry in data["entries"]:
        assert entry["group"] in groups, entry["code"]
        assert entry["kind"] in {"groovyscope", "checker", "codenarc", "profile"}
        assert entry["severity"] in {"error", "warning", "info"}
        assert entry["groovy"] in {"5", "6", "both"}
        assert entry["why"] and entry["fix"] and entry["section"]


@pytest.mark.skipif(not DOCS.is_file(), reason="repository checkout required")
def test_every_section_link_resolves() -> None:
    anchors = {slug(line.lstrip("#")) for line in DOCS.read_text(encoding="utf-8").splitlines() if line.startswith("#")}
    data = json.loads(CATALOG.read_text(encoding="utf-8"))
    missing = [(e["code"], e["section"]) for e in data["entries"] if e["section"] not in anchors]
    missing += [(g["id"], g["section"]) for g in data["groups"] if g["section"] not in anchors]
    assert missing == []
