"""scripts/homebrew_formula.py renders a formula from a PyPI sdist."""

from __future__ import annotations

import importlib.util
import io
import json
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "homebrew_formula.py"


def load_script() -> ModuleType:
    spec = importlib.util.spec_from_file_location("homebrew_formula", SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_render_mentions_jdk_and_virtualenv() -> None:
    script = load_script()
    formula = script.render("https://files.pythonhosted.org/x/groovyscope-0.6.0.tar.gz", "ab" * 32)
    assert 'url "https://files.pythonhosted.org/x/groovyscope-0.6.0.tar.gz"' in formula
    assert f'sha256 "{"ab" * 32}"' in formula
    assert 'depends_on "openjdk@21"' in formula and "virtualenv_create" in formula
    assert "JAVA_HOME" in formula and 'license all_of: ["Apache-2.0", "EPL-2.0"' in formula


def test_main_looks_up_pypi_and_writes_the_file(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    script = load_script()
    payload = {
        "urls": [
            {
                "packagetype": "bdist_wheel",
                "url": "https://x/groovyscope-0.6.0-py3-none-any.whl",
                "digests": {"sha256": "ff"},
            },
            {"packagetype": "sdist", "url": "https://x/groovyscope-0.6.0.tar.gz", "digests": {"sha256": "cd" * 32}},
        ]
    }

    class _Response(io.BytesIO):
        def __enter__(self) -> _Response:
            return self

        def __exit__(self, *_args: Any) -> None:
            self.close()

    def fake_urlopen(url: str, timeout: int) -> _Response:
        if url.endswith("groovyscope-requirements.txt"):  # a release from before the hashed requirements
            raise script.urllib.error.HTTPError(url, 404, "Not Found", {}, None)  # type: ignore[arg-type]
        assert url == "https://pypi.org/pypi/groovyscope/0.6.0/json"
        return _Response(json.dumps(payload).encode("utf-8"))

    monkeypatch.setattr(script.urllib.request, "urlopen", fake_urlopen)
    output = tmp_path / "groovyscope.rb"
    assert script.main(["--version", "0.6.0", "--output", str(output)]) == 0
    text = output.read_text(encoding="utf-8")
    assert 'url "https://x/groovyscope-0.6.0.tar.gz"' in text and f'sha256 "{"cd" * 32}"' in text


def test_main_gives_up_when_pypi_has_no_sdist(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    script = load_script()

    def failing(url: str, timeout: int) -> Any:
        raise OSError("not yet")

    monkeypatch.setattr(script.urllib.request, "urlopen", failing)
    with pytest.raises(SystemExit, match=r"does not serve 0\.6\.0 yet"):
        script.main(["--version", "0.6.0", "--wait", "0"])
    assert script.main(["--version", "0.6.0", "--url", "https://x/s.tar.gz", "--sha256", "ab" * 32]) == 0
    assert 'url "https://x/s.tar.gz"' in capsys.readouterr().out


HASHED = """# exported by uv
anyio==4.15.1 \\
    --hash=sha256:6152fdbbf9a77fdec97731721bebf7c4c44f7c29b424b0065826173efc7ed101
    # via mcp
click==8.3.0 ; sys_platform != 'win32' \\
    --hash=sha256:abcd
"""


def test_render_installs_the_dependencies_from_hashed_requirements_only() -> None:
    script = load_script()
    formula = script.render("https://x/groovyscope-0.6.0.tar.gz", "ab" * 32, HASHED)
    assert "--require-hashes" in formula and "--no-deps" in formula
    assert "<<~'REQUIREMENTS'" in formula and "--hash=sha256:6152fdbb" in formula
    assert 'license all_of: ["Apache-2.0", "EPL-2.0"' in formula
    with pytest.raises(ValueError, match="click"):
        script.render("https://x/s.tar.gz", "ab" * 32, "anyio==1 \\\n    --hash=sha256:aa\nclick==8.3.0\n")


def test_main_takes_the_hashed_requirements_from_the_release(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    script = load_script()
    seen: list[str] = []

    class _Response(io.BytesIO):
        def __enter__(self) -> _Response:
            return self

        def __exit__(self, *_args: Any) -> None:
            self.close()

    def fake_urlopen(url: str, timeout: int) -> _Response:
        seen.append(url)
        if url.endswith("groovyscope-requirements.txt"):
            if "0.7.0" in url:
                raise script.urllib.error.HTTPError(url, 404, "Not Found", {}, None)  # type: ignore[arg-type]
            return _Response(HASHED.encode("utf-8"))
        raise AssertionError(url)

    monkeypatch.setattr(script.urllib.request, "urlopen", fake_urlopen)
    args = ["--url", "https://x/s.tar.gz", "--sha256", "ab" * 32]
    assert script.main(["--version", "0.8.0", *args]) == 0
    assert "--require-hashes" in capsys.readouterr().out
    assert (
        seen[-1] == "https://github.com/SCGIS-Wales/groovyscope/releases/download/v0.8.0/groovyscope-requirements.txt"
    )
    assert script.main(["--version", "0.7.0", *args]) == 0
    captured = capsys.readouterr()
    assert "--require-hashes" not in captured.out and "no hashed requirements" in captured.err
