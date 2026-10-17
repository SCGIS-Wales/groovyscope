from __future__ import annotations

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
