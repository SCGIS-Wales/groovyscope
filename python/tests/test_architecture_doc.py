"""docs/architecture.md names every Python module and every engine Java package, so it cannot fall behind the code."""

from __future__ import annotations

from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
DOC = REPO / "docs" / "architecture.md"
PACKAGE = REPO / "python" / "src" / "groovyscope"
ENGINE = REPO / "engine" / "src" / "main" / "java" / "org" / "groovyscope" / "engine"
# Entry points and generated files that carry no responsibility of their own.
EXEMPT = {"__init__.py", "__main__.py"}


def python_modules() -> list[str]:
    """Each module as the doc names it: `name.py` at the top level, `package/name.py` below it."""
    return sorted(
        path.relative_to(PACKAGE).as_posix()
        for path in PACKAGE.rglob("*.py")
        if "__pycache__" not in path.parts and path.name not in EXEMPT
    )


def engine_packages() -> list[str]:
    """Each engine package that holds Java sources, dotted below `org.groovyscope.engine` (the root by its full name)."""
    found = {path.parent.relative_to(ENGINE).as_posix().replace("/", ".") for path in ENGINE.rglob("*.java")}
    return sorted("org.groovyscope.engine" if package == "." else package for package in found)


@pytest.mark.skipif(not DOC.is_file() or not ENGINE.is_dir(), reason="repository checkout required")
def test_every_python_module_is_named() -> None:
    text = DOC.read_text(encoding="utf-8")
    missing = [module for module in python_modules() if f"`{module}`" not in text]
    assert not missing, f"docs/architecture.md does not name {missing}"


@pytest.mark.skipif(not DOC.is_file() or not ENGINE.is_dir(), reason="repository checkout required")
def test_every_engine_package_is_named() -> None:
    text = DOC.read_text(encoding="utf-8")
    missing = [package for package in engine_packages() if f"`{package}`" not in text]
    assert not missing, f"docs/architecture.md does not name the engine package(s) {missing}"
