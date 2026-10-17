"""check and format against the real engine through the daemon (needs a JDK and the JAR)."""

from __future__ import annotations

from pathlib import Path

import pytest
from click.testing import CliRunner

from groovyscope import cli, daemon
from tests.test_integration_lsp import engine_available

pytestmark = pytest.mark.integration

MESSY = """package sample

import java.util.List;

class Messy {
    String name = "plain";

    String greet() {
        return "hello " + name;
    }
}
"""


@pytest.mark.skipif(not engine_available(), reason="needs a JDK and an engine JAR (bundled or GROOVYSCOPE_JAR)")
def test_check_and_format_through_the_daemon(tmp_path: Path, short_dir: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    workspace = tmp_path / "ws"
    source = workspace / "src" / "main" / "groovy" / "sample" / "Messy.groovy"
    source.parent.mkdir(parents=True)
    source.write_text(MESSY, encoding="utf-8")
    monkeypatch.setenv(daemon.SOCKET_DIR_ENV, str(short_dir))
    monkeypatch.setenv(daemon.CACHE_DIR_ENV, str(tmp_path / "cache"))
    monkeypatch.setenv("GROOVYSCOPE_DAEMON__IDLETIMEOUTMINUTES", "1")
    runner = CliRunner()
    try:
        checked = runner.invoke(cli.main, ["--root", str(workspace), "check", "--include", "lint"])
        assert checked.exit_code == 1, checked.output
        assert "codenarc/UnnecessarySemicolon" in checked.output
        assert "codenarc/UnnecessaryGroovyImport" in checked.output

        compile_only = runner.invoke(cli.main, ["--root", str(workspace), "check", "--include", "compile"])
        assert compile_only.exit_code == 0, compile_only.output

        dry = runner.invoke(cli.main, ["--root", str(workspace), "format"])
        assert dry.exit_code == 1, dry.output
        assert "need formatting" in dry.output

        applied = runner.invoke(cli.main, ["--root", str(workspace), "format", "--apply"])
        assert applied.exit_code == 0, applied.output
        text = source.read_text(encoding="utf-8")
        assert 'String name = "plain"\n' in text
        assert "import java.util.List\n" in text
        assert "   \n" not in text

        again = runner.invoke(cli.main, ["--root", str(workspace), "format"])
        assert again.exit_code == 0, again.output
    finally:
        runner.invoke(cli.main, ["--root", str(workspace), "daemon", "stop"])
