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
    assert "JAVA_HOME" in formula and 'license "Apache-2.0"' in formula


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
