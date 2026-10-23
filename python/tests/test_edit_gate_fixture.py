"""The edit gate's fixture project exists once for the engine's specs and once for these tests, byte for byte."""

from __future__ import annotations

from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent / "fixtures" / "edit-gate"
ENGINE = Path(__file__).resolve().parents[2] / "engine" / "src" / "test" / "resources" / "projects" / "edit-gate"


def files(root: Path) -> dict[str, bytes]:
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(root.rglob("*")) if p.is_file()}


@pytest.mark.skipif(not ENGINE.is_dir(), reason="repository checkout required")
def test_the_two_copies_of_the_fixture_are_identical() -> None:
    assert files(HERE) == files(ENGINE)


def test_the_fixture_holds_one_file_per_deliberate_case() -> None:
    names = set(files(HERE))
    assert {
        "src/main/groovy/demo/Verbose.groovy",
        "src/main/groovy/demo/Types.groovy",
        "src/main/groovy/demo/Copied.groovy",
        "src/main/groovy/demo/Strings.groovy",
        "src/test/groovy/demo/SubjectSpec.groovy",
        "src/it/groovy/demo/SubjectIT.groovy",
    } <= names
    subject = (HERE / "src/main/groovy/demo/Subject.groovy").read_text().count("\n")
    spec = (HERE / "src/test/groovy/demo/SubjectSpec.groovy").read_text().count("\n")
    assert spec >= 4 * subject  # a test class four times its subject
