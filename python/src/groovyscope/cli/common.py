"""What every command shares: logging, the configuration handed to the engine, starting and reaching the daemon, engine
calls and the rows commands print. The test seams (``_RPC``, ``_EXEC``, ``_RELAY`` and the rest) live here, and the
other modules reach them as ``common.<name>``, so patching this module patches every use."""

from __future__ import annotations

import hashlib
import importlib
import json
import logging
import os
import sys
from collections.abc import Callable, Mapping, Sequence
from importlib import resources
from pathlib import Path
from typing import Any

import click

from groovyscope import (
    __version__,
    atomic,
    diagnostics,
    kiro_agent,
    practices,
    profiles,
    provision,
    relay,
    render,
    reports,
    rpc,
    workspace_init,
)
from groovyscope import classpath as classpath_export
from groovyscope import config as config_module
from groovyscope import daemon as daemon_lifecycle
from groovyscope import paths as workspace_paths
from groovyscope.config import load_config, load_config_with_notes
from groovyscope.jar import locate_jar
from groovyscope.java import JdkInfo, discover
from groovyscope.launch import MIN_JDK_FOR_AUTO_CDS, build_engine_command

LOG_ENV = config_module.LOG_ENV


LOG = logging.getLogger("groovyscope.cli")


CONFIG_ENV = config_module.CONFIG_JSON_ENV


BASELINE_PATH = Path(".groovyscope") / "contract-baseline.json"


LEVEL_MARKS = {"ok": "ok  ", "warn": "warn", "fail": "FAIL", "info": "info"}


ExecFn = Callable[[str, Sequence[str]], Any]


def configure_logging(
    environ: dict[str, str] | None = None, *, level: str | None = None, log_file: Path | None = None
) -> None:
    """``--debug``/``--verbose`` win over ``GROOVYSCOPE_LOG``; the default is warnings only."""
    env = os.environ if environ is None else environ
    diagnostics.configure(diagnostics.level_of(level or env.get(LOG_ENV)), log_file)


def apply_config_logging(obj: dict[str, Any], effective: dict[str, Any]) -> None:
    """``logging.level``/``logging.file`` from the configuration apply when no flag or GROOVYSCOPE_LOG chose already,
    so the LSP and MCP processes Kiro starts honour .groovyscope.yaml. Flags reach the engine the same way."""
    section = effective.setdefault("logging", {})
    if obj.get("log_level"):
        section["level"] = obj["log_level"]
    if obj.get("log_file"):
        section["file"] = str(obj["log_file"])
    if not obj.get("log_level") and not os.environ.get(LOG_ENV):
        file_setting = section.get("file")
        diagnostics.configure(diagnostics.level_of(section.get("level")), Path(file_setting) if file_setting else None)


@click.group(context_settings={"help_option_names": ["-h", "--help"]})
@click.version_option(__version__, prog_name="groovyscope")
@click.option(
    "--config",
    "config_path",
    type=click.Path(path_type=Path, dir_okay=False),
    default=None,
    envvar=config_module.CONFIG_FILE_ENV,
    help="Path to .groovyscope.yaml (default: ./.groovyscope.yaml when present).",
)
@click.option(
    "--root",
    type=click.Path(path_type=Path, file_okay=False, exists=True),
    default=None,
    help="Workspace root (default: current directory).",
)
@click.option("--debug", is_flag=True, help="Debug logging here and in the engine (implies --verbose).")
@click.option("-v", "--verbose", is_flag=True, help="Report each step (JDK, JAR, configuration, daemon, downloads).")
@click.option(
    "--log-file",
    type=click.Path(path_type=Path, dir_okay=False),
    default=None,
    help="Also append log records to this file (the engine too, when it is under the groovyscope cache).",
)
@click.pass_context
def main(
    ctx: click.Context,
    *,
    config_path: Path | None,
    root: Path | None,
    debug: bool,
    verbose: bool,
    log_file: Path | None,
) -> None:
    """Groovy 5/6 code intelligence for AI coding agents."""
    level = "debug" if debug else "info" if verbose else None
    configure_logging(level=level, log_file=log_file)
    ctx.obj = {
        "config_path": config_path,
        "root": (root or Path.cwd()).resolve(),
        "log_level": level,
        "log_file": log_file,
    }
    LOG.debug("groovyscope %s on Python %s, root %s", __version__, sys.version.split()[0], ctx.obj["root"])


def config_hash_or_none(obj: dict[str, Any]) -> str | None:
    """The hash of the configuration this process would hand to the engine, or None when it cannot be loaded."""
    try:
        effective = load_config(obj["root"], obj["config_path"], dict(os.environ))
    except (ValueError, OSError):
        return None
    hand_over_config(obj, effective)
    return config_hash(obj)


def cds_archive_for(
    effective: dict[str, Any], jdk: JdkInfo, jar_sha256: str | None, environ: dict[str, str]
) -> Path | None:
    """The CDS archive to use, or None when disabled, unsupported by the JDK, or the JAR is unverified."""
    if (
        not effective["daemon"].get("cds", config_module.default("daemon", "cds"))
        or jdk["version"] < MIN_JDK_FOR_AUTO_CDS
        or not jar_sha256
    ):
        return None
    return daemon_lifecycle.cache_dir(environ) / "cds" / f"engine-{jar_sha256[:16]}.jsa"


def resolve_jdk(effective: dict[str, Any], environ: dict[str, str]) -> JdkInfo | None:
    """A JDK meeting ``jdk.minimum``; with ``jdk.autoProvision`` on, Temurin is downloaded when none is found."""
    minimum = int(effective["jdk"]["minimum"])
    provisioned = provision.provisioned_homes(provision.jdk_dir(environ))
    found = discover(effective["jdk"].get("home"), environ, minimum=minimum, provisioned=provisioned)
    for note in found["notes"]:
        LOG.warning(f"groovyscope: {note}")
    jdk = found["jdk"]
    LOG.debug("JDK: %s", jdk)
    if jdk is not None and jdk["version"] >= minimum:
        return jdk
    if not effective["jdk"].get("autoProvision"):
        return None
    feature = max(minimum, provision.DEFAULT_FEATURE)
    LOG.warning(f"groovyscope: no JDK {minimum}+ found; jdk.autoProvision is on, downloading Temurin {feature}")
    try:
        installed = provision.provision_jdk(
            provision.jdk_dir(environ),
            feature=feature,
            vendor=str(effective["jdk"].get("vendor", config_module.default("jdk", "vendor"))),
        )
    except (OSError, ValueError, KeyError) as exc:
        LOG.warning(f"groovyscope: JDK provisioning failed: {exc}")
        return None
    return {"home": installed["home"], "version": installed["version"], "source": "provisioned"}


def resolve_engine(obj: dict[str, Any]) -> tuple[list[str], dict[str, Any]]:
    """Resolve configuration, JDK and JAR; exit 1 with a message on any failure."""
    try:
        effective, notes = load_config_with_notes(obj["root"], obj["config_path"], dict(os.environ))
    except (ValueError, OSError) as exc:
        LOG.error(str(exc))
        sys.exit(1)
    for note in notes:
        LOG.warning(f"groovyscope: {note}")
    apply_config_logging(obj, effective)
    LOG.debug(
        "configuration: %s; workspace %s; `groovyscope config validate --show` prints it",
        obj["config_path"] or "defaults + .groovyscope.yaml when present",
        "trusted" if effective["workspace"].get("trust") else "untrusted",
    )
    jdk = resolve_jdk(effective, dict(os.environ))
    if jdk is None:
        LOG.error("groovyscope: no suitable JDK found; run `groovyscope doctor`")
        sys.exit(1)
    jar = locate_jar(dict(os.environ))
    LOG.debug("engine JAR: %s", jar)
    if jar["error"] or jar["path"] is None:
        LOG.error(f"groovyscope: {jar['error']}")
        sys.exit(1)
    archive = cds_archive_for(effective, jdk, jar["actual_sha256"], dict(os.environ))
    if archive is not None:
        archive.parent.mkdir(parents=True, exist_ok=True)
    dumps = daemon_lifecycle.cache_dir(dict(os.environ)) / "dumps"
    command = build_engine_command(
        Path(jdk["home"]), Path(jar["path"]), effective, [], cds_archive=archive, dump_dir=dumps
    )
    obj["engine_version"] = jar["version"]
    obj["jdk_home"] = jdk["home"]
    LOG.debug("engine command: %s", " ".join(command))
    return (command, effective)


def bundled_config(relative: str) -> Path:
    """A configuration file shipped inside the package (rulesets, formatter settings)."""
    return Path(str(resources.files("groovyscope").joinpath("_templates/config").joinpath(relative)))


def engine_view(
    obj: dict[str, Any], effective: dict[str, Any], runtime_profiles: Sequence[dict[str, Any]] = ()
) -> dict[str, Any]:
    """The effective configuration with bundled defaults resolved to real paths and the runtime profiles
    discovered for the workspace, as the engine needs it."""
    lint = dict(effective["lint"])
    lint["ruleset"] = (
        str(Path(lint["ruleset"]).expanduser())
        if lint.get("ruleset")
        else str(bundled_config("codenarc/groovyscope.groovy"))
    )
    lint["jenkinsRuleset"] = (
        str(Path(lint["jenkinsRuleset"]).expanduser())
        if lint.get("jenkinsRuleset")
        else str(bundled_config("codenarc/jenkins.groovy"))
    )
    lint["gradleRuleset"] = (
        str(Path(lint["gradleRuleset"]).expanduser())
        if lint.get("gradleRuleset")
        else str(bundled_config("codenarc/gradle.groovy"))
    )
    workspace = workspace_init.with_source_sets(Path(obj["root"]), dict(effective.get("workspace", {})))
    listed = workspace.get("classpathFile")
    if listed:
        exported = classpath_export.read_file(Path(obj["root"]) / Path(listed).expanduser())
        workspace["classpath"] = list(dict.fromkeys([*workspace.get("classpath", []), *exported]))
    return {
        **effective,
        "lint": lint,
        "workspace": workspace,
        "root": str(obj["root"]),
        "profiles": list(runtime_profiles),
    }


def discover_profiles(obj: dict[str, Any], effective: dict[str, Any]) -> profiles.Discovery:
    """Runtime profiles for the workspace; problems are reported on stderr and the profile is skipped."""
    found = profiles.discover(obj["root"], effective.get("profiles", {}), Path.home())
    for problem in found["errors"]:
        LOG.warning(f"groovyscope: profile skipped: {problem}")
    return found


def hand_over_config(obj: dict[str, Any], effective: dict[str, Any]) -> str:
    """The validated, effective configuration for the engine (see EngineConfig), as JSON. It is kept in ``obj``
    (never in this process's environment, which MCP threads share) and written, private and atomically, to the
    file the engine is started with (``--config``): a long classpath would not fit in an environment variable."""
    found = discover_profiles(obj, effective)
    trusted = bool(effective["workspace"].get("trust"))
    if not trusted:
        for note in profiles.untrusted_jar_notes(found, obj["root"]):
            LOG.warning(f"groovyscope: {note}")
    runtime = profiles.engine_profiles(found, obj["root"], trusted=trusted)
    text = json.dumps(engine_view(obj, effective, runtime), sort_keys=True)
    target = engine_config_file(obj["root"], dict(os.environ))
    daemon_lifecycle.secure_dir(target.parent)
    atomic.write_text(target, text, mode=atomic.PRIVATE_MODE)
    obj["engine_config"] = text
    obj["engine_config_file"] = target
    return text


ENV_CONFIG_LIMIT_BYTES = 96 * 1024  # Linux refuses one environment string over 128 KiB (MAX_ARG_STRLEN)


def engine_config_file(root: Path, environ: Mapping[str, str]) -> Path:
    """Where the engine configuration of a workspace is written for the engine to read."""
    return daemon_lifecycle.cache_dir(environ) / "config" / f"{daemon_lifecycle.workspace_key(root)}.json"


def engine_environ(obj: dict[str, Any], environ: Mapping[str, str]) -> dict[str, str]:
    """The environment for an engine process: ``environ`` with the configuration in GROOVYSCOPE_CONFIG_JSON when it
    is small enough to fit (older engines read only that), and without it otherwise (``--config`` carries it)."""
    env = dict(environ)
    text = str(obj.get("engine_config") or "")
    if text and len(text.encode("utf-8")) <= ENV_CONFIG_LIMIT_BYTES:
        env[CONFIG_ENV] = text
    else:
        env.pop(CONFIG_ENV, None)
    return env


def config_arguments(obj: dict[str, Any]) -> list[str]:
    """``--config <file>`` when the configuration was written, for the engine command line."""
    target = obj.get("engine_config_file")
    return ["--config", str(target)] if target else []


def daemon_paths(root: Path) -> tuple[Path, Path, Path]:
    """``(socket, log, cache)`` for a workspace root."""
    environ = dict(os.environ)
    return (
        daemon_lifecycle.socket_path(root, environ),
        daemon_lifecycle.log_path(root, environ),
        daemon_lifecycle.cache_dir(environ),
    )


def config_hash(obj: dict[str, Any]) -> str:
    """SHA-256 of the configuration handed to the engine, as the engine reports it in its status."""
    return hashlib.sha256(str(obj.get("engine_config") or "").encode("utf-8")).hexdigest()


RELOAD_METHOD = rpc.RELOAD_METHOD


RELOAD_TIMEOUT_SECONDS = 60.0


RELOAD_UNCHANGED = "unchanged"


RELOADED = "reloaded"


RELOAD_FAILED = "failed"


def reload_if_stale(obj: dict[str, Any], socket_file: Path, status: dict[str, Any]) -> str:
    """A daemon running another configuration (a re-exported classpath, an edited .groovyscope.yaml, changed
    profiles) is handed the current one and applies it in place. ``unchanged`` when it already runs this one,
    ``reloaded`` when a reload was sent and accepted, ``failed`` when the engine could not take it (the user is
    told how to restart) or reports no configuration hash to compare."""
    running = status.get("configHash")
    if not (isinstance(running, str) and running):
        return RELOAD_FAILED
    if running == config_hash(obj):
        return RELOAD_UNCHANGED
    try:
        _RPC(socket_file, RELOAD_METHOD, {"config": str(obj.get("engine_config") or "")}, RELOAD_TIMEOUT_SECONDS)
    except (OSError, RuntimeError, ValueError) as exc:
        LOG.warning(
            f"groovyscope: the daemon runs an older configuration and could not reload it ({exc}); "
            "run `groovyscope daemon restart` to apply it"
        )
        return RELOAD_FAILED
    LOG.info("daemon configuration reloaded in place")
    return RELOADED


def config_source(obj: dict[str, Any]) -> Callable[[], tuple[str, str] | None]:
    """A function the MCP server calls before each engine request: the current effective configuration as
    ``(hash, json)``, recomputed only when the configuration file or the classpath file changed on disk (stat
    calls otherwise), or None when the configuration cannot be loaded (the engine keeps the last good one)."""
    memo: dict[str, Any] = {"stamp": None, "effective": None}
    value: list[tuple[str, str] | None] = [None]

    def stamps() -> tuple[tuple[int, int] | None, ...]:
        config_file = obj["config_path"] or obj["root"] / ".groovyscope.yaml"
        listed = (memo.get("effective") or {}).get("workspace", {}).get("classpathFile")
        classpath_file = obj["root"] / Path(listed or classpath_export.DEFAULT_FILE).expanduser()
        found: list[tuple[int, int] | None] = []
        for file in (config_file, classpath_file):
            try:
                stat = file.stat()
                found.append((stat.st_mtime_ns, stat.st_size))
            except OSError:
                found.append(None)
        return tuple(found)

    def current() -> tuple[str, str] | None:
        stamp = stamps()
        if stamp == memo["stamp"]:
            return value[0]
        try:
            effective = load_config(obj["root"], obj["config_path"], dict(os.environ))
            text = hand_over_config(obj, effective)
        except (ValueError, OSError) as exc:
            LOG.warning("configuration not reloaded: %s", exc)
            memo["stamp"] = stamp
            return value[0]
        memo.update({"stamp": stamp, "effective": effective})
        value[0] = (config_hash(obj), text)
        return value[0]

    return current


def ensure_daemon(obj: dict[str, Any], command: list[str], effective: dict[str, Any]) -> tuple[Path, dict[str, Any]]:
    """Start the workspace daemon when needed (replacing one from another engine version). Raises on failure."""
    socket_file, log_file, cache = daemon_paths(obj["root"])
    daemon_command = daemon_lifecycle.daemon_command(
        command,
        socket_file,
        int(effective["daemon"]["idleTimeoutMinutes"]),
        cache,
        obj["root"],
        config_file=obj.get("engine_config_file"),
    )
    status = daemon_lifecycle.ensure_running(
        daemon_command,
        socket_file,
        log_file,
        engine_environ(obj, os.environ),
        expected_engine=obj.get("engine_version"),
        settings=effective.get("daemon", {}),
    )
    reload_if_stale(obj, socket_file, status)
    return (socket_file, status)


def run_through_daemon(obj: dict[str, Any], command: list[str], effective: dict[str, Any]) -> int | None:
    """Start the daemon if needed and relay stdio to it. None when that is impossible (caller falls back). A daemon
    that answered the probe but is gone by the time the relay connects (it exited idle, `daemon stop` elsewhere)
    is started once more; nothing from stdin is read before the connection stands, so nothing is lost."""
    for attempt in range(2):
        try:
            socket_file, _ = ensure_daemon(obj, command, effective)
            return _RELAY(socket_file)
        except daemon_lifecycle.CrashLoopRefusedError as exc:
            # A JVM started directly would die the same way: say why and start nothing.
            LOG.error(f"groovyscope: {exc}")
            sys.exit(1)
        except OSError as exc:
            if attempt == 0:
                LOG.warning(f"groovyscope: daemon went away before the session started ({exc})")
                LOG.warning("groovyscope: starting it again")
                continue
            LOG.warning(f"groovyscope: daemon unavailable ({exc}); running the engine directly")
        except RuntimeError as exc:
            LOG.warning(f"groovyscope: daemon unavailable ({exc}); running the engine directly")
            return None
    return None


def exec_process(path: str, argv: Sequence[str]) -> None:
    """Replace this process with the engine (never returns on success)."""
    os.execv(path, list(argv))  # noqa: S606 - deliberate: the CLI becomes the engine process


_EXEC: ExecFn = exec_process


_KIRO: Callable[[], kiro_agent.KiroRunner | None] = kiro_agent.kiro_runner


_RELAY: Callable[[Path], int] = relay.main


_RPC: Callable[..., Any] = rpc.request


_PROBE: Callable[[Path], dict[str, Any] | None] = daemon_lifecycle.probe


def mcp_module() -> Any:
    """The MCP server module, imported on first use: it pulls in the MCP SDK (about a second of start-up), which
    `hook`, `checkup` and the other short commands Kiro runs on every turn never need."""
    return importlib.import_module("groovyscope.mcp_server")


def serve_mcp(*args: Any, **kwargs: Any) -> int:
    return int(mcp_module().serve(*args, **kwargs))


_MCP_SERVE: Callable[..., int] = serve_mcp


RPC_TIMEOUT_SECONDS = 600.0


SEVERITY_NAMES = render.SEVERITY_NAMES


POSITION_PARTS = 3  # path:line:col


def daemon_socket_for(obj: dict[str, Any]) -> tuple[Path, dict[str, Any]]:
    """Make sure the workspace daemon runs and return its socket with the effective configuration."""
    try:
        return start_daemon_for(obj)
    except (OSError, RuntimeError) as exc:
        LOG.error(f"groovyscope: {exc}")
        sys.exit(1)


def start_daemon_for(obj: dict[str, Any]) -> tuple[Path, dict[str, Any]]:
    """``daemon_socket_for`` that raises instead of exiting (``CrashLoopRefusedError`` names what to change)."""
    command, effective = resolve_engine(obj)
    hand_over_config(obj, effective)
    socket_file, _ = ensure_daemon(obj, command, effective)
    return (socket_file, effective)


def to_uris(root: Path, paths: Sequence[Path]) -> list[str]:
    """The URIs of the files a command names; one outside the workspace stops the command."""
    try:
        return workspace_paths.to_uris(root, paths)
    except ValueError as exc:
        raise click.ClickException(str(exc)) from exc


uri_to_path = workspace_paths.uri_to_path


def engine_call(
    socket_file: Path, method: str, params: dict[str, Any], timeout: float = RPC_TIMEOUT_SECONDS
) -> dict[str, Any]:
    result = engine_result(socket_file, method, params, timeout)
    return dict(result) if isinstance(result, dict) else {}


def engine_result(socket_file: Path, method: str, params: dict[str, Any], timeout: float = RPC_TIMEOUT_SECONDS) -> Any:
    """One engine request, whatever shape it answers with (a list for impact reports); exits on failure."""
    try:
        return _RPC(socket_file, method, params, timeout)
    except (OSError, RuntimeError, ValueError) as exc:
        LOG.error(f"groovyscope: {method} failed: {exc}")
        sys.exit(1)


def imported_rows(
    root: Path, effective: Mapping[str, Any], explicit: Sequence[Path], paths: Sequence[Path]
) -> list[dict[str, Any]]:
    """SARIF findings as CLI rows (the build's reports plus EXPLICIT files), limited to PATHS when given; files
    that cannot be read are reported on stderr and skipped."""
    found, notes = reports.merge_sarif(root, reports.reports_settings(effective)["sarif"], explicit=list(explicit))
    for note in notes:
        LOG.warning(f"groovyscope: {note}")
    wanted = [shown_path(root / p, root).rstrip("/") for p in paths]
    rows = []
    for finding in found:
        if wanted and not any(finding["path"] == w or finding["path"].startswith(w + "/") for w in wanted):
            continue
        rows.append(
            {
                "path": finding["path"],
                "line": finding["line"],
                "column": finding["col"],
                "severity": finding["severity"],
                "level": practices.LEVELS.get(str(finding["severity"]), 3),
                "source": finding["source"],
                "code": finding["code"],
                "message": finding["message"],
            }
        )
    return rows


def finding_rows(diagnostics: dict[str, list[dict[str, Any]]], root: Path) -> list[dict[str, Any]]:
    """Flatten engine diagnostics into sortable rows with workspace-relative paths and 1-based positions."""
    rows: list[dict[str, Any]] = []
    for uri, items in diagnostics.items():
        path = uri_to_path(uri)
        try:
            shown = path.relative_to(root).as_posix()
        except ValueError:
            shown = path.as_posix()
        for item in items:
            start = item.get("range", {}).get("start", {})
            severity = int(item.get("severity", 3))
            rows.append(
                {
                    "path": shown,
                    "line": int(start.get("line", 0)) + 1,
                    "column": int(start.get("character", 0)) + 1,
                    "severity": SEVERITY_NAMES.get(severity, "info"),
                    "level": severity,
                    "source": item.get("source", ""),
                    "code": item.get("code", ""),
                    "message": item.get("message", ""),
                }
            )
    rows.sort(key=lambda row: (row["path"], row["line"], row["column"], row["level"]))
    return rows


def tool_settings(effective: Mapping[str, Any], classpath: Mapping[str, Any] | None = None) -> dict[str, Any]:
    """What the MCP tools read beyond the output section: duplicate thresholds, the build's reports and the
    classpath file to watch."""
    return {
        **effective["output"],
        "duplicates": effective.get("duplicates", {}),
        "diagram": effective.get("diagram", {}),
        "reports": reports.reports_settings(effective),
        "verify": effective.get("verify", {}),
        "classpath": dict(classpath or {}),
        "mcp": dict(effective.get("mcp") or {}),
    }


def trusted_now(obj: dict[str, Any]) -> bool:
    """Whether the workspace is trusted at this moment (the configuration read again, so a revoke counts)."""
    try:
        effective = load_config(obj["root"], obj["config_path"], dict(os.environ))
    except (OSError, ValueError):
        return False
    return bool(effective["workspace"].get("trust"))


def classpath_settings(obj: dict[str, Any], effective: Mapping[str, Any]) -> dict[str, Any]:
    """Where the exported classpath lives, whether jars are listed by hand, whether the workspace is trusted
    (so `refresh_classpath` may run the build), which JDK the build should use, how long the build may run and
    how long the MCP tool waits for it before answering that it still runs."""
    workspace = effective.get("workspace", {})
    listed = workspace.get("classpathFile") or classpath_export.DEFAULT_FILE
    budget = float(
        (effective.get("mcp") or {}).get("requestTimeoutSeconds", config_module.default("mcp", "requestTimeoutSeconds"))
    )
    return {
        "file": str(obj["root"] / Path(listed).expanduser()),
        "configured": bool(workspace.get("classpath")),
        "trusted": bool(workspace.get("trust")),
        "isTrusted": lambda: trusted_now(obj),
        "javaHome": obj.get("jdk_home"),
        "timeoutSeconds": export_timeout(effective),
        "waitSeconds": max(1.0, budget - EXPORT_REPLY_MARGIN_SECONDS),
        "progressSeconds": float(
            (effective.get("mcp") or {}).get("progressSeconds", config_module.default("mcp", "progressSeconds"))
        ),
    }


EXPORT_REPLY_MARGIN_SECONDS = 10.0  # refresh_classpath answers this long before the call's budget runs out


def export_timeout(effective: Mapping[str, Any]) -> int:
    """``classpath.timeoutSeconds``: how long a classpath export (the build) may run."""
    section = effective.get("classpath") or {}
    return max(1, int(section.get("timeoutSeconds", config_module.default("classpath", "timeoutSeconds"))))


def workspace_tools(obj: dict[str, Any], socket_file: Path, effective: dict[str, Any]) -> dict[str, Any]:
    """The MCP tool functions bound to this workspace over the daemon socket, so CLI and agent answers match."""
    tools: dict[str, Any] = mcp_module().build_tools(
        obj["root"],
        lambda method, params: engine_result(socket_file, method, params),
        tool_settings(effective),
        dict(os.environ),
    )
    return tools


def shown_path(path: Path, root: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.as_posix()


def target_params(obj: dict[str, Any], target: str) -> dict[str, Any]:
    """``path:line:col`` (1-based) becomes a uri with a zero-based position; anything else is a symbol name."""
    parts = target.rsplit(":", 2)
    if len(parts) == POSITION_PARTS and parts[1].isdigit() and parts[2].isdigit():
        path = (obj["root"] / parts[0]).resolve()
        return {"uri": path.as_uri(), "line": int(parts[1]) - 1, "character": int(parts[2]) - 1}
    return {"symbol": target}
