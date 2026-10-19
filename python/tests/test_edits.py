from __future__ import annotations

from pathlib import Path

import pytest

from groovyscope import edits as edits_module
from groovyscope.edits import apply_edits, changed_line_count, line_starts, offset_of


def edit(sl: int, sc: int, el: int, ec: int, text: str) -> dict[str, object]:
    return {"range": {"start": {"line": sl, "character": sc}, "end": {"line": el, "character": ec}}, "newText": text}


def test_offsets() -> None:
    text = "ab\ncd\n"
    starts = line_starts(text)
    assert starts == [0, 3, 6]
    assert offset_of(text, starts, 1, 1) == 4
    assert offset_of(text, starts, 1, 99) == 5
    assert offset_of(text, starts, 99, 0) == 6


def test_apply_edits_in_any_order() -> None:
    text = "hello world\nsecond line\n"
    edits = [edit(0, 0, 0, 5, "goodbye"), edit(1, 7, 1, 11, "row"), edit(0, 6, 0, 11, "there")]
    assert apply_edits(text, edits) == "goodbye there\nsecond row\n"
    assert apply_edits(text, []) == text
    assert apply_edits(text, [edit(0, 0, 2, 0, "x\n")]) == "x\n"


def test_changed_line_count() -> None:
    assert changed_line_count("a\nb\nc\n", "a\nB\nc\n") == 1
    assert changed_line_count("a\nb\n", "a\nb\nc\nd\n") == 2
    assert changed_line_count("same", "same") == 0
    # One inserted line near the top is one changed line, not every line after it.
    body = "\n".join(f"line {n}" for n in range(120))
    assert changed_line_count(body, "import x\n" + body) == 1
    assert changed_line_count("a\nb\nc\n", "a\nc\n") == 1


def test_write_document_refuses_a_file_that_changed_since_it_was_read(tmp_path: Path) -> None:
    target = tmp_path / "A.groovy"
    target.write_text("class A {}\n")
    edits_module.write_document(target, "class A { }\n", expected="class A {}\n")
    assert target.read_text() == "class A { }\n"
    target.write_text("class A { int x }\n")  # somebody saved in between
    with pytest.raises(edits_module.FileChangedError, match="changed on disk"):
        edits_module.write_document(target, "class B {}\n", expected="class A { }\n")
    assert target.read_text() == "class A { int x }\n"
    edits_module.write_document(target, "class C {}\n")  # without an expectation the write is unconditional
    assert target.read_text() == "class C {}\n"


def test_write_document_keeps_a_symbolic_link_and_writes_through_it(tmp_path: Path) -> None:
    real = tmp_path / "src" / "A.groovy"
    real.parent.mkdir()
    real.write_text("class A {}\r\n")
    real.chmod(0o640)
    link = tmp_path / "Linked.groovy"
    link.symlink_to(real)
    edits_module.write_document(link, "class A { }\r\n", expected="class A {}\r\n")
    assert link.is_symlink() and real.read_bytes() == b"class A { }\r\n"
    assert real.stat().st_mode & 0o777 == 0o640


def test_a_save_that_lands_while_the_edit_is_written_wins(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    target = tmp_path / "A.groovy"
    target.write_text("class A {}\n")
    reading = edits_module.read_document

    def read_then_someone_saves(path: Path) -> str:
        text = reading(path)
        target.write_text("class A { int saved }\n")  # the editor saves right after the check
        return text

    monkeypatch.setattr(edits_module, "read_document", read_then_someone_saves)
    with pytest.raises(edits_module.FileChangedError, match="while the edit was being written"):
        edits_module.write_document(target, "class B {}\n", expected="class A {}\n")
    assert target.read_text() == "class A { int saved }\n"
    assert [p.name for p in tmp_path.iterdir()] == ["A.groovy"]
