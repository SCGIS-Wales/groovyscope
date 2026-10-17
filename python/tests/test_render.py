from __future__ import annotations

from pathlib import Path

from hypothesis import given
from hypothesis import strategies as st

from groovyscope.render import compact_json, encoded_size, page, relativize, table

items_strategy = st.lists(st.dictionaries(st.text(max_size=5), st.text(max_size=20), max_size=3), max_size=30)


def test_relativize_inside_and_outside(tmp_path: Path) -> None:
    inside = tmp_path / "src" / "A.groovy"
    assert relativize(inside, tmp_path) == "src/A.groovy"
    outside = tmp_path.parent / "elsewhere.groovy"
    assert relativize(outside, tmp_path) == outside.resolve().as_posix()


def test_compact_json_is_sorted_and_tight() -> None:
    assert compact_json({"b": 1, "a": [1, 2]}) == '{"a":[1,2],"b":1}'
    assert encoded_size({"é": 1}) == len('{"é":1}'.encode())


@given(items_strategy, st.integers(min_value=1, max_value=10), st.integers(min_value=256, max_value=2000))
def test_page_respects_limits(items: list[dict[str, str]], max_results: int, max_bytes: int) -> None:
    result = page(items, max_results=max_results, max_bytes=max_bytes)
    assert len(result["items"]) <= max_results
    assert result["total"] == len(items)
    if len(result["items"]) > 1:
        assert encoded_size(result["items"]) <= max_bytes
    if items:
        assert result["items"], "always makes progress"
    assert result["truncated"] == (result["next_cursor"] is not None)


@given(items_strategy, st.integers(min_value=1, max_value=5))
def test_page_cursor_walks_every_item(items: list[dict[str, str]], max_results: int) -> None:
    cursor: int | None = 0
    seen: list[dict[str, str]] = []
    while cursor is not None:
        result = page(items, max_results=max_results, max_bytes=100_000, cursor=cursor)
        seen.extend(result["items"])
        cursor = result["next_cursor"]
    assert seen == items


def test_page_cursor_is_clamped() -> None:
    result = page([1, 2, 3], max_results=10, max_bytes=1000, cursor=99)
    assert result == {"items": [], "total": 3, "truncated": False, "next_cursor": None}
    result = page([1, 2, 3], max_results=10, max_bytes=1000, cursor=-5)
    assert result["items"] == [1, 2, 3]


def test_table() -> None:
    assert table([]) == ""
    rendered = table([("ok", "jdk", "21"), ("FAIL", "jar", "missing")])
    assert rendered == "ok    jdk  21\nFAIL  jar  missing"
