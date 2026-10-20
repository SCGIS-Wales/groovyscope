"""Conflict hunks: parsing (with leftovers from hand edits), suggestions, choices and the joins of both sides."""

from __future__ import annotations

import pytest

from groovyscope import conflicts

BOTH_ADDED_TESTS = """class T {
    @Test
    void a() {
<<<<<<< HEAD
        assertThat(result).containsExactly('MFA_All_Time')
=======
    // Phase 1
    @Test
    void venafi() {
        try {
            go()
        } finally {
            srv.stop(0)
        }
>>>>>>> feat/mvp-5
    }
}
"""


def hunk_text(ours: str, theirs: str, base: str | None = None) -> str:
    middle = f"||||||| base\n{base}" if base is not None else ""
    return f"before\n<<<<<<< HEAD\n{ours}{middle}=======\n{theirs}>>>>>>> feat\nafter\n"


def test_a_hunk_is_parsed_with_its_labels_sides_and_lines() -> None:
    parsed = conflicts.parse(hunk_text("a\n", "b\n", base="o\n"))
    (hunk,) = parsed["hunks"]
    assert (hunk["start"], hunk["end"], hunk["oursLabel"], hunk["theirsLabel"]) == (1, 7, "HEAD", "feat")
    assert (hunk["ours"], hunk["base"], hunk["theirs"]) == (["a\n"], ["o\n"], ["b\n"])
    assert parsed["leftovers"] == [] and parsed["newline"] == "\n"
    assert conflicts.parse(hunk_text("a\r\n", "b\r\n").replace("\n", "\r\n"))["newline"] == "\r\n"


def test_markers_left_by_a_hand_edit_are_leftovers_and_underlines_are_content() -> None:
    half = BOTH_ADDED_TESTS.replace("<<<<<<< HEAD\n", "")
    parsed = conflicts.parse(half)
    assert parsed["hunks"] == []
    assert [item["line"] for item in parsed["leftovers"]] == [5, 14]
    assert conflicts.has_markers(half)
    assert conflicts.parse("Title\n=======\ntext\n")["leftovers"] == []  # a Markdown underline
    assert not conflicts.has_markers("LICENCE\n==================================\n")
    unclosed = "<<<<<<< HEAD\na\n=======\nb\n"
    assert [item["line"] for item in conflicts.parse(unclosed)["leftovers"]] == [1]
    nested = "<<<<<<< HEAD\na\n<<<<<<< HEAD\nb\n=======\nc\n>>>>>>> x\n"
    parsed = conflicts.parse(nested)
    assert [item["line"] for item in parsed["leftovers"]] == [1] and len(parsed["hunks"]) == 1
    assert [item["line"] for item in conflicts.parse("a\n>>>>>>> x\n||||||| y\n")["leftovers"]] == [2, 3]


@pytest.mark.parametrize(
    ("ours", "theirs", "base", "take"),
    [
        ("same\n", "same\n", None, "ours"),
        ("old\n", "new\n", "old\n", "theirs"),
        ("new\n", "old\n", "old\n", "ours"),
        ("  a  b\n", "a b\n", None, "ours"),
        ("import a.B\n", "import c.D\n", None, "both"),
        ("void x() {}\n", "void y() {}\n", "", "both"),
    ],
)
def test_a_hunk_one_side_did_not_change_is_suggested(ours: str, theirs: str, base: str | None, take: str) -> None:
    (hunk,) = conflicts.parse(hunk_text(ours, theirs, base))["hunks"]
    suggestion = conflicts.suggest(hunk)
    assert suggestion is not None and suggestion["take"] == take


def test_a_real_disagreement_has_no_suggestion() -> None:
    (hunk,) = conflicts.parse(hunk_text("x = 1\n", "x = 2\n", "x = 0\n"))["hunks"]
    assert conflicts.suggest(hunk) is None
    (hunk,) = conflicts.parse(hunk_text("x = 1\n", "x = 2\n"))["hunks"]
    assert conflicts.suggest(hunk) is None


def test_each_choice_gives_its_lines_and_render_keeps_other_hunks() -> None:
    text = hunk_text("import a.B\n", "import a.B\nimport c.D\n", "o\n") + hunk_text("1\n", "2\n")
    parsed = conflicts.parse(text)
    first = parsed["hunks"][0]
    assert conflicts.side_lines(first, "both") == ["import a.B\n", "import c.D\n"]  # each import once
    assert conflicts.side_lines(first, "theirs-then-ours") == ["import a.B\n", "import c.D\n"]
    assert conflicts.side_lines(first, "base") == ["o\n"]
    assert conflicts.side_lines(first, "neither") == []
    with pytest.raises(ValueError, match="no base"):
        conflicts.side_lines(parsed["hunks"][1], "base")
    rendered = conflicts.render(parsed, {1: ["x\n"]})
    assert rendered.startswith("before\nx\nafter\nbefore\n<<<<<<< HEAD\n1\n")
    assert conflicts.render(parsed, {}) == text
    assert conflicts.as_lines("a\nb", "\r\n") == ["a\r\n", "b\r\n"] and conflicts.as_lines("", "\n") == []


def test_keeping_both_added_tests_repeats_the_brace_git_moved_out_first() -> None:
    parsed = conflicts.parse(BOTH_ADDED_TESTS)
    (hunk,) = parsed["hunks"]
    variants = conflicts.both_variants(parsed, hunk, "both", 3)
    note, lines = variants[0]
    assert note.startswith("repeats 1 line(s) after the hunk")
    assert conflicts.render(parsed, {1: lines}) == (
        "class T {\n    @Test\n    void a() {\n        assertThat(result).containsExactly('MFA_All_Time')\n    }\n\n"
        "    // Phase 1\n    @Test\n    void venafi() {\n        try {\n            go()\n        } finally {\n"
        "            srv.stop(0)\n        }\n    }\n}\n"
    )
    assert variants[1][0] == "plain"
    assert len(variants) == 1 + 2 + 3 + 6  # plain, two closings, three openings and their pairs


def test_aligned_sides_join_plainly_first_and_deeper_second_side_repeats_the_opening() -> None:
    parsed = conflicts.parse(hunk_text("    a()\n", "    b()\n"))
    assert conflicts.both_variants(parsed, parsed["hunks"][0], "both", 2)[0][0] == "plain"
    text = "class C {\n    void m() {\n<<<<<<< HEAD\n    }\n=======\n        x()\n>>>>>>> f\n    }\n}\n"
    parsed = conflicts.parse(text)
    note, _ = conflicts.both_variants(parsed, parsed["hunks"][0], "both", 3)[0]
    assert note.startswith("repeats 1 line(s) before the hunk")


def test_numbered_elides_the_middle_past_the_limit() -> None:
    rows = conflicts.numbered([f"{i}\n" for i in range(10)], 5, 4)
    assert rows == ["5| 0", "6| 1", "... 6 line(s) not shown ...", "13| 8", "14| 9"]
    assert conflicts.numbered(["a\n"], 1, 4) == ["1| a"]
