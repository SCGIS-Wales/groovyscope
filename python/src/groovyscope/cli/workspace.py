"""Setting groovyscope up in a workspace: version, doctor, checkup, config, trust, init, the JDK, the classpath export
and runtime profiles."""

from __future__ import annotations

import os
import sys
from collections.abc import Callable
from pathlib import Path
from typing import Any

import click

from groovyscope import (
    __version__,
    checkup,
    diagnostics,
    profiles,
    provision,
    trust,
    workspace_init,
)
from groovyscope import classpath as classpath_export
from groovyscope import daemon as daemon_lifecycle
from groovyscope import fetch as fetch_engine
from groovyscope.cli import common
from groovyscope.cli import quality as quality_commands
from groovyscope.config import dump_yaml, load_config
from groovyscope.doctor import build_report, has_failures
from groovyscope.jar import read_manifest
from groovyscope.java import discover
from groovyscope.kiro import (
    backup_path,
    text_diff,
    write_atomically,
)
from groovyscope.render import compact_json, table


@common.main.command("checkup")
@click.option(
    "--agent",
    "for_agent",
    is_flag=True,
    help="The compact block the Kiro agent's start hook injects: reports and offers the repairs, writes nothing, "
    "never fails.",
)
@click.option("--fix", is_flag=True, help="Apply the safe repairs (Kiro files, project LSP entry, benchmark corpus).")
@click.option("--json", "as_json", is_flag=True, help="Machine-readable report.")
@click.pass_obj
def checkup_command(obj: dict[str, Any], *, for_agent: bool, fix: bool, as_json: bool) -> None:
    """Everything to do after an install or upgrade, as one report with the exact fix per item."""
    environ = dict(os.environ)
    if for_agent and not checkup.is_project(obj["root"]):
        # The start hook of any agent groovyscope is attached to: in a folder with no Groovy, nothing to check or
        # repair, and nothing to install on the user's machine.
        click.echo(checkup.IDLE_NOTE)
        return
    if for_agent:
        quality_commands.session_baseline(obj)
    try:
        report = checkup.build(
            obj["root"],
            obj["config_path"],
            environ,
            Path.home(),
            fix=fix,  # the start hook (--agent) only reports and offers; --fix writes
            run=None if for_agent else common._KIRO(),
            config_hash=common.config_hash_or_none(obj),
        )
    except Exception as exc:
        if for_agent:
            click.echo(f"groovyscope checkup could not run: {exc}")
            return
        raise
    if for_agent:
        click.echo(checkup.render_agent(report))
        return
    if as_json:
        click.echo(compact_json(report))
        return
    for line in checkup.render(report):
        click.echo(line)
    if any(item["level"] == "fail" for item in report["items"]):
        sys.exit(1)


@common.main.command()
def version() -> None:
    """Print the package and bundled engine versions."""
    manifest = read_manifest()
    engine = manifest.get("version", "unknown") if manifest else "not bundled"
    click.echo(f"groovyscope {__version__}")
    click.echo(f"engine {engine}")
    if manifest:
        click.echo(f"codenarc {manifest.get('codenarc', 'unknown')}")


@common.main.command()
@click.option(
    "--bundle",
    type=click.Path(path_type=Path, dir_okay=False),
    default=None,
    help="Also write a zip for a bug report: this report, versions, configuration, daemon log, Kiro's groovyscope "
    "log lines (no source code, no profile contents, no Kiro chat).",
)
@click.pass_obj
def doctor(obj: dict[str, Any], bundle: Path | None) -> None:
    """Check the JDK, engine JAR, configuration, Kiro files and profiles; exit 1 on hard failures."""
    report = build_report(obj["root"], obj["config_path"], dict(os.environ), kiro=common._KIRO())
    click.echo(table([(common.LEVEL_MARKS[level], name, message) for level, name, message in report]))
    if bundle is not None:
        try:
            effective: dict[str, Any] | None = load_config(obj["root"], obj["config_path"], dict(os.environ))
        except (ValueError, OSError):
            effective = None
        manifest = read_manifest()
        written = diagnostics.bundle(
            bundle,
            doctor_lines=diagnostics.lines_of(report),
            versions={
                "groovyscope": __version__,
                "engine": (manifest or {}).get("version"),
                "codenarc": (manifest or {}).get("codenarc"),
            },
            effective_config=effective,
            daemon_log=common.daemon_paths(obj["root"])[1],
            environ=dict(os.environ),
        )
        click.echo(f"diagnostics bundle written to {written}")
    if has_failures(report):
        sys.exit(1)


@common.main.group()
def config() -> None:
    """Inspect the effective configuration."""


@config.command("validate")
@click.option("--show", is_flag=True, help="Print the effective configuration as YAML.")
@click.pass_obj
def config_validate(obj: dict[str, Any], show: bool) -> None:
    """Merge defaults, file and environment, then validate against the schema."""
    try:
        effective = load_config(obj["root"], obj["config_path"], dict(os.environ))
    except (ValueError, OSError) as exc:
        common.LOG.error(str(exc))
        sys.exit(1)
    if show:
        click.echo(dump_yaml(effective), nl=False)
    else:
        click.echo("configuration OK")


@common.main.command()
@click.option(
    "--from",
    "source",
    default=None,
    help="URL (https) or local path of the engine JAR. Default: the GitHub Release matching this package.",
)
@click.option("--sha256", default=None, help="Expected SHA-256; otherwise SHA256SUMS.txt next to the JAR is used.")
@click.option("--engine-version", default=__version__, show_default=True, help="Engine version being fetched.")
def fetch(source: str | None, sha256: str | None, engine_version: str) -> None:
    """Fetch the engine JAR into the cache for installs that do not bundle one (checksum verified)."""
    environ = dict(os.environ)
    destination = fetch_engine.fetched_dir(environ)
    chosen = source or fetch_engine.default_source(engine_version)
    try:
        result = fetch_engine.fetch_jar(chosen, version=engine_version, destination=destination, sha256=sha256)
    except (OSError, ValueError) as exc:
        common.LOG.error(f"groovyscope: fetch failed: {exc}")
        sys.exit(1)
    click.echo(f"fetched {result['path']} (version {result['version']}, sha256 {result['sha256'][:16]}...)")
    click.echo(f"from {result['source']}")


@common.main.group()
def jdk() -> None:
    """Provision a Temurin JDK when none is installed (never done unless asked or jdk.autoProvision is on)."""


@jdk.command("install")
@click.option("--feature", type=int, default=provision.DEFAULT_FEATURE, show_default=True, help="Java feature release.")
@click.option(
    "--vendor", type=click.Choice(list(provision.VENDORS)), default="temurin", show_default=True, help="JDK vendor."
)
def jdk_install(feature: int, vendor: str) -> None:
    """Download the latest JDK of a feature release (Eclipse Temurin or Amazon Corretto), verify its SHA-256 against
    the vendor's published checksum, and unpack it into groovyscope's cache."""
    environ = dict(os.environ)
    try:
        installed = provision.provision_jdk(provision.jdk_dir(environ), feature=feature, vendor=vendor)
    except (OSError, ValueError, KeyError) as exc:
        common.LOG.error(f"groovyscope: JDK provisioning failed: {exc}")
        sys.exit(1)
    click.echo(f"installed {installed['release']} at {installed['home']}")
    click.echo("groovyscope will use it when no other JDK meets jdk.minimum; `groovyscope doctor` shows which wins")


@common.main.command("classpath")
@click.option("--tool", type=click.Choice(["gradle", "maven"]), default=None, help="Build tool (default: detected).")
@click.option(
    "--timeout", type=int, default=None, help="Seconds the build may run (default: classpath.timeoutSeconds)."
)
@click.pass_obj
def classpath_command(obj: dict[str, Any], tool: str | None, timeout: int | None) -> None:
    """Ask the project's Gradle or Maven build for its compile and test classpath and write it to
    workspace.classpathFile, so dependencies resolve. Runs the build tool, so only in a trusted workspace."""
    try:
        effective = load_config(obj["root"], obj["config_path"], dict(os.environ))
    except (ValueError, OSError) as exc:
        common.LOG.error(str(exc))
        sys.exit(1)
    if not effective["workspace"].get("trust"):
        common.LOG.error(
            "groovyscope: `classpath` runs the project's build tool, which executes its build scripts; "
            "run `groovyscope trust` first if you trust this workspace"
        )
        sys.exit(1)
    environ = dict(os.environ)
    if not environ.get("JAVA_HOME"):
        jdk = discover(effective["jdk"].get("home"), environ, minimum=int(effective["jdk"]["minimum"]))["jdk"]
        if jdk is not None:
            environ["JAVA_HOME"] = jdk["home"]
    common.LOG.warning("running the build tool to resolve the classpath (this can take a while)…")
    target = obj["root"] / Path(effective["workspace"].get("classpathFile") or classpath_export.DEFAULT_FILE)
    try:
        with classpath_export.exclusive(target):
            result = classpath_export.export(
                obj["root"], environ, tool=tool, timeout=timeout or common.export_timeout(effective)
            )
            classpath_export.write_file(target, result, fingerprint=classpath_export.build_fingerprint(obj["root"]))
    except ValueError as exc:
        common.LOG.error(f"groovyscope: {exc}")
        sys.exit(1)
    click.echo(
        f"{len(result['paths'])} classpath entries from {result['tool']} written to "
        f"{common.shown_path(target, obj['root'])}"
    )
    if result["missing"]:
        common.LOG.warning(f"{len(result['missing'])} listed path(s) do not exist yet (not built?) and were left out")
    socket_file = common.daemon_paths(obj["root"])[0]
    status = daemon_lifecycle.probe(socket_file)
    if status is not None:
        common.hand_over_config(obj, load_config(obj["root"], obj["config_path"], dict(os.environ)))
        outcome = common.reload_if_stale(obj, socket_file, status)
        if outcome == common.RELOADED:
            click.echo("the running daemon reloaded the new classpath; the next request recompiles against it")
        elif outcome == common.RELOAD_UNCHANGED:
            click.echo("the running daemon already runs this classpath; nothing to reload")
        else:
            try:
                daemon_lifecycle.stop(socket_file)
            except (OSError, RuntimeError) as exc:
                common.LOG.warning(f"groovyscope: {exc}; run `groovyscope daemon restart` to apply the classpath")
            else:
                click.echo("the running daemon was stopped; the next request starts it with the new classpath")


@common.main.command("init")
@click.option("--dry-run", is_flag=True, help="Show the diff without writing anything.")
@click.pass_obj
def init_command(obj: dict[str, Any], dry_run: bool) -> None:
    """Write a starting .groovyscope.yaml from the workspace's layout and build files (existing settings win)."""
    try:
        target, current, after = workspace_init.plan(obj["root"])
    except (ValueError, OSError) as exc:
        common.LOG.error(f"groovyscope: {exc}")
        sys.exit(1)
    if current == after:
        click.echo(f"{common.shown_path(target, obj['root'])} already has these settings")
    else:
        click.echo(text_diff(current, after, common.shown_path(target, obj["root"])), nl=False)
        if dry_run:
            click.echo("dry run: nothing written")
        else:
            if current is not None:
                write_atomically(backup_path(target), current)
            write_atomically(target, after)
            click.echo(f"wrote {common.shown_path(target, obj['root'])}")
    click.echo("next: `groovyscope trust` (if you trust this workspace), then `groovyscope classpath`")


@common.main.group("profile")
def profile_group() -> None:
    """Runtime profiles: which are active, whether a file is valid, what governs a file."""


@profile_group.command("list")
@click.pass_obj
def profile_list(obj: dict[str, Any]) -> None:
    """List the runtime profiles found for this workspace and where they came from; exit 1 on invalid ones."""
    try:
        effective = load_config(obj["root"], obj["config_path"], dict(os.environ))
    except (ValueError, OSError) as exc:
        common.LOG.error(str(exc))
        sys.exit(1)
    found = profiles.discover(obj["root"], effective.get("profiles", {}), Path.home())
    for line in profiles.describe(found):
        click.echo(line)
    if found["errors"]:
        sys.exit(1)


@profile_group.command("install")
@click.argument("file", type=click.Path(path_type=Path, exists=True, dir_okay=False))
@click.option("--force", is_flag=True, help="Replace a profile file of the same name.")
def profile_install(file: Path, force: bool) -> None:
    """Validate a runtime profile and copy it to ~/.groovyscope/profiles/ (readable by you only), the way to bring
    a private profile to another machine without putting it in a repository."""
    try:
        problems = profiles.validate_profile(profiles.read_profile(file))
    except (OSError, ValueError) as exc:
        problems = (str(exc),)
    if problems:
        for problem in problems:
            common.LOG.error(f"{file}: {problem}")
        sys.exit(1)
    folder = Path.home() / profiles.USER_DIR
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    target = folder / file.name
    if target.exists() and not force:
        common.LOG.error(f"groovyscope: {target} exists; pass --force to replace it")
        sys.exit(1)
    write_atomically(target, file.read_text(encoding="utf-8"))
    target.chmod(0o600)
    click.echo(f"installed {target}; `groovyscope daemon restart` in open workspaces to apply it")


@profile_group.command("validate")
@click.argument("files", nargs=-1, required=True, type=click.Path(path_type=Path, exists=True, dir_okay=False))
def profile_validate(files: tuple[Path, ...]) -> None:
    """Validate profile files against the profile schema."""
    failed = False
    for file in files:
        try:
            problems = profiles.validate_profile(profiles.read_profile(file))
        except (OSError, ValueError) as exc:
            problems = (str(exc),)
        for problem in problems:
            common.LOG.warning(f"{file}: {problem}")
        failed = failed or bool(problems)
        if not problems:
            click.echo(f"{file}: ok")
    if failed:
        sys.exit(1)


@profile_group.command("status")
@click.argument("path", type=click.Path(path_type=Path, dir_okay=False))
@click.option("--json", "as_json", is_flag=True, help="Machine-readable output.")
@click.pass_obj
def profile_status(obj: dict[str, Any], path: Path, as_json: bool) -> None:
    """What the governing runtime profile says about one file (PATH relative to the workspace root)."""
    if not (obj["root"] / path).is_file():
        common.LOG.error(f"groovyscope: {path} is not a file under {obj['root']}")
        sys.exit(2)
    socket_file, _ = common.daemon_socket_for(obj)
    status = common.engine_call(
        socket_file, "groovyscope/profile", {"root": str(obj["root"]), "uri": common.to_uris(obj["root"], [path])[0]}
    )
    if as_json:
        click.echo(compact_json(status))
        return
    if not status.get("governed"):
        click.echo(f"{common.shown_path(path.resolve(), obj['root'])}: not governed by a runtime profile")
        return
    for key, value in status.items():
        if key != "governed" and value not in ("", [], {}, None):
            click.echo(f"{key}: {value}")


def stdin_is_terminal() -> bool:
    return sys.stdin.isatty()


_IS_TTY: Callable[[], bool] = stdin_is_terminal


@common.main.command("trust")
@click.option("--revoke", is_flag=True, help="Stop trusting the workspace.")
@click.option("--list", "list_all", is_flag=True, help="List the trusted workspaces.")
@click.option(
    "--yes-i-am-the-user",
    "confirmed",
    is_flag=True,
    help="Grant trust from a terminal that an agent session started (you typed this, not the agent).",
)
@click.pass_obj
def trust_command(obj: dict[str, Any], *, revoke: bool, list_all: bool, confirmed: bool) -> None:
    """Trust this workspace: its .groovyscope.yaml may then set workspace.trust (AST transformations from the
    workspace classpath), jdk.home, CodeNarc rule sets, logging.file, JDK downloads and heap dumps, and its runtime
    profiles may load emulation jars. Granting trust needs a terminal: an agent cannot grant it to itself."""
    home = Path.home()
    if list_all:
        for root in trust.trusted_roots(home):
            click.echo(root)
        return
    if not revoke:
        marker = trust.agent_session(os.environ)
        if not _IS_TTY():
            common.LOG.error(
                "groovyscope trust: refused without a terminal; trust lets this workspace run code, so run it "
                "yourself in a terminal"
            )
            sys.exit(2)
        if marker is not None and not confirmed:
            common.LOG.error(
                f"groovyscope trust: refused inside an agent session ({marker} is set); if you are typing this "
                "yourself, add --yes-i-am-the-user"
            )
            sys.exit(2)
    trust.set_trusted(obj["root"], home, trusted=not revoke)
    state = "no longer trusted" if revoke else "trusted"
    click.echo(f"{obj['root']} is {state}; run `groovyscope daemon restart` if a daemon is serving it")
