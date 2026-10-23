"""Which lines of a Groovy text are comment, which are code, and where the Javadoc blocks are: a small lexer that
knows line and block comments and the four Groovy string quotes, enough to count comment lines without a parse."""

from __future__ import annotations

from typing import TypedDict


class LineKinds(TypedDict):
    comment: set[int]  # 1-based lines holding only comment text
    code: set[int]  # 1-based lines holding code (a trailing comment counts as code)
    javadoc: list[tuple[int, int]]  # 1-based first and last line of each /** */ block


def line_kinds(text: str) -> LineKinds:
    """Which lines are comment, which code, and where the Javadoc blocks are."""
    comment: set[int] = set()
    code: set[int] = set()
    javadoc: list[tuple[int, int]] = []
    line, index, length = 1, 0, len(text)
    while index < length:
        char = text[index]
        if char == "\n":
            line += 1
            index += 1
        elif text.startswith("//", index):
            end = text.find("\n", index)
            end = length if end < 0 else end
            comment.add(line)
            index = end
        elif text.startswith("/*", index):
            end = text.find("*/", index + 2)
            end = length if end < 0 else end + 2
            first = line
            line += text.count("\n", index, end)
            comment.update(range(first, line + 1))
            if text.startswith("/**", index):
                javadoc.append((first, line))
            index = end
        elif char in "'\"":
            quote = text[index : index + 3] if text.startswith(char * 3, index) else char
            end = text.find(quote, index + len(quote))
            while end > 0 and text[end - 1] == "\\" and len(quote) == 1:
                end = text.find(quote, end + 1)
            end = length if end < 0 else end + len(quote)
            code.add(line)
            line += text.count("\n", index, end)
            code.add(line)
            index = end
        else:
            if not char.isspace():
                code.add(line)
            index += 1
    return {"comment": comment - code, "code": code, "javadoc": javadoc}
