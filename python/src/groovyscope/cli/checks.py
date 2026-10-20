"""The Kiro checks kit: a throwaway workspace with the corpus, and the report of what is ready."""

from __future__ import annotations

import contextlib
import os
import shutil
import subprocess
import sys
import tempfile
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

import click

from groovyscope import (
    __version__,
    checks,
    process,
    trust,
)
from groovyscope.cli import common
from groovyscope.cli import kiro as kiro_commands
from groovyscope.doctor import build_report
from groovyscope.jar import read_manifest


@common.main.group("checks")
def checks_group() -> None:
    """The owner's Kiro checks (docs/kiro-checks.md): a throwaway workspace and a report to paste back."""


_CHECKS_RUNNER: checks.Runner = checks.default_runner


@checks_group.command("prepare")
@click.argument("directory", required=False, type=click.Path(path_type=Path, file_okay=False))
@click.option("--force", is_flag=True, help="Replace a workspace an earlier `checks prepare` made (never another).")
@click.pass_obj
def checks_prepare(obj: dict[str, Any], directory: Path | None, *, force: bool) -> None:
    """Make the workspace every Kiro check runs in (default: <tmp>/gs-check): the CodeNarc corpus, a build.gradle,
    a Jenkinsfile, git history with a `change` branch to review, the project's Kiro LSP entry and trust. Your
    shell stays where it is."""
    target = (directory or Path(tempfile.gettempdir()) / checks.DEFAULT_DIRECTORY).resolve()
    if target.exists():
        if not force or not checks.is_checks_workspace(target):
            reason = (
                "it was not made by `groovyscope checks prepare`, so it is left alone"
                if force
                else "pass --force to replace it"
            )
            common.LOG.error(f"{target} exists; {reason}")
            sys.exit(1)
        shutil.rmtree(target)
    corpus = kiro_commands.unpack_benchmark(dict(os.environ))
    if corpus is None:
        sys.exit(1)
    try:
        done = checks.prepare(target, corpus, _CHECKS_RUNNER)
    except (OSError, subprocess.CalledProcessError) as exc:
        common.LOG.error(f"groovyscope checks prepare: {exc}")
        sys.exit(1)
    for line in done:
        click.echo(line)
    kiro_commands.run_kiro_merge({**obj, "root": target, "config_path": None}, force=False, backup=False, dry_run=False)
    trust.set_trusted(target, Path.home(), trusted=True)
    click.echo(f"{target} is trusted")
    for line in checks.next_steps(target):
        click.echo(line)


HOOK_PROBE_SECONDS = 120.0  # far above a healthy answer; only a hung hook reaches it


def hook_seconds(root: Path) -> float:
    """How long `groovyscope hook` takes to start and answer a Stop event in ``root``: what Kiro waits for after
    every write and at the end of every turn."""
    command = [sys.executable, "-c", "from groovyscope.cli import main; main()", "--root", str(root), "hook"]
    started = time.monotonic()
    with contextlib.suppress(subprocess.TimeoutExpired):
        process.run_text([*command, "--event", "stop"], stdin_text="{}", timeout=HOOK_PROBE_SECONDS)
    return time.monotonic() - started


_HOOK_TIMER: Callable[[Path], float] = hook_seconds


@checks_group.command("report")
@click.pass_obj
def checks_report(obj: dict[str, Any]) -> None:
    """Print the block to paste back after the Kiro checks: versions, the doctor lines (the MCP client and whether
    it can be asked included), the hook start-up time, and the result table to fill in."""
    manifest = read_manifest() or {}
    facts = {
        "groovyscope": __version__,
        "engine": str(manifest.get("version", "not bundled")),
        "codenarc": str(manifest.get("codenarc", "unknown")),
        "workspace": str(obj["root"]),
        "hook start-up": f"{_HOOK_TIMER(obj['root']):.2f} s (Kiro waits this long after every write and turn)",
    }
    rows = build_report(obj["root"], obj["config_path"], dict(os.environ), kiro=common._KIRO())
    for line in checks.report(facts, rows):
        click.echo(line)
