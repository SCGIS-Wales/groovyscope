"""Debug logging, log viewing and the diagnostics bundle for bug reports.

Python side: ``--debug`` / ``--verbose`` / ``--log-file`` on any command, ``GROOVYSCOPE_LOG``, or ``logging.level`` and
``logging.file`` in ``.groovyscope.yaml`` (which also reach the LSP and MCP processes Kiro starts). Engine side: the
same ``logging`` settings are handed to the JVM; a daemon writes to its log under the cache directory.
"""

from __future__ import annotations

import functools
import io
import json
import logging
import os
import platform
import sys
import tempfile
import time
import zipfile
from collections.abc import Callable, Iterable, Mapping, Sequence
from pathlib import Path
from typing import Any

FORMAT = "%(asctime)s %(levelname)s %(name)s: %(message)s"
LEVELS = {"debug": logging.DEBUG, "info": logging.INFO, "warning": logging.WARNING, "error": logging.ERROR}
ROOT_LOGGER = "groovyscope"
KIRO_LOG_FILES = ("lsp.log", "mcp.log")  # never kiro-chat.log: it holds conversation text
KIRO_MARKERS = ("groovyscope", "groovy")
ENV_KEYS = ("JAVA_HOME", "PATH", "KIRO_HOME", "XDG_RUNTIME_DIR", "XDG_CACHE_HOME", "SHELL", "TERM")
SECRET_HINTS = ("TOKEN", "SECRET", "PASSWORD", "KEY", "CREDENTIAL")
CONTROL_LIMIT = 0x20  # code points below are C0 control characters
DELETE = 0x7F


def level_of(name: str | None, default: int = logging.WARNING) -> int:
    return LEVELS.get((name or "").lower(), default)


def escape_controls(text: str) -> str:
    """Control characters (CR, LF and the rest of C0, DEL) as escapes, so a file name or message read from the
    workspace cannot start a line of its own in a log."""
    return "".join(
        ch if ch == "\t" or (ord(ch) >= CONTROL_LIMIT and ord(ch) != DELETE) else ch.encode("unicode_escape").decode()
        for ch in text
    )


def one_line(record: logging.LogRecord) -> logging.LogRecord:
    """A logging filter: the record's message and traceback escaped onto one line each (see escape_controls)."""
    record.msg = escape_controls(record.getMessage())
    record.args = None
    if record.exc_info:
        record.exc_text = escape_controls(logging.Formatter().formatException(record.exc_info))
        record.exc_info = None
    return record


def configure(level: int, log_file: Path | None = None) -> None:
    """Send groovyscope's log records to stderr at ``level`` and, when given, to ``log_file`` (appending). A log
    file that cannot be opened is reported once and left out; logging goes on to stderr."""
    logger = logging.getLogger(ROOT_LOGGER)
    for handler in list(logger.handlers):
        logger.removeHandler(handler)
        handler.close()
    stderr = logging.StreamHandler(sys.stderr)
    stderr.setFormatter(logging.Formatter(FORMAT if level <= logging.INFO else "%(message)s"))
    stderr.addFilter(one_line)
    logger.addHandler(stderr)
    if log_file is not None:
        try:
            log_file.expanduser().parent.mkdir(parents=True, exist_ok=True)
            file_handler = logging.FileHandler(log_file.expanduser(), encoding="utf-8")
        except OSError as exc:
            stderr.handle(logging.makeLogRecord({"msg": f"groovyscope: cannot write the log file {log_file}: {exc}",
                                                 "levelno": logging.WARNING, "levelname": "WARNING"}))  # fmt: skip
        else:
            file_handler.setFormatter(logging.Formatter(FORMAT))
            file_handler.addFilter(one_line)
            logger.addHandler(file_handler)
    logger.setLevel(level)
    logger.propagate = False


def tail(path: Path, lines: int) -> list[str]:
    """The last ``lines`` lines of a text file (empty when it does not exist)."""
    try:
        with path.open("rb") as handle:
            handle.seek(0, os.SEEK_END)
            size = handle.tell()
            handle.seek(max(0, size - max(1, lines) * 400))
            text = handle.read().decode("utf-8", "replace")
    except OSError:
        return []
    return text.splitlines()[-lines:] if lines > 0 else []


def kiro_log_dir(environ: Mapping[str, str]) -> Path:
    """Where Kiro CLI writes its logs (``$TMPDIR/kiro-log``)."""
    return Path(environ.get("TMPDIR") or tempfile.gettempdir()) / "kiro-log"


def kiro_lines(environ: Mapping[str, str], lines: int) -> dict[str, list[str]]:
    """groovyscope-related lines from Kiro's LSP and MCP logs."""
    found: dict[str, list[str]] = {}
    folder = kiro_log_dir(environ)
    for name in KIRO_LOG_FILES:
        matching = [line for line in tail(folder / name, lines * 20) if any(m in line.lower() for m in KIRO_MARKERS)]
        found[name] = matching[-lines:]
    return found


def follow(
    path: Path,
    emit: Callable[[str], None],
    *,
    sleep: Callable[[float], None] = time.sleep,
    stop: Callable[[], bool] = lambda: False,
) -> None:
    """Print lines appended to ``path`` until ``stop`` says so (Ctrl-C in the CLI)."""
    position = path.stat().st_size if path.exists() else 0
    while not stop():
        if path.exists() and path.stat().st_size > position:
            with path.open("rb") as handle:
                handle.seek(position)
                chunk = handle.read()
                position = handle.tell()
            for line in chunk.decode("utf-8", "replace").splitlines():
                emit(line)
        sleep(0.5)


def safe_environment(environ: Mapping[str, str]) -> dict[str, str]:
    """The environment variables that matter for diagnosis, without anything that looks secret."""
    selected = {key: environ[key] for key in ENV_KEYS if key in environ}
    for key, value in environ.items():
        if key.startswith("GROOVYSCOPE_") and key != "GROOVYSCOPE_CONFIG_JSON":
            selected[key] = "<redacted>" if any(hint in key for hint in SECRET_HINTS) else value
    return dict(sorted(selected.items()))


def bundle(
    target: Path,
    *,
    doctor_lines: Sequence[str],
    versions: Mapping[str, Any],
    effective_config: Mapping[str, Any] | None,
    daemon_log: Path,
    environ: Mapping[str, str],
    extra: Mapping[str, str] | None = None,
    log_lines: int = 2000,
) -> Path:
    """Write a zip a user can attach to a bug report. It holds no source code, no profile contents (names and
    paths only) and none of Kiro's chat log."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("doctor.txt", "\n".join(doctor_lines) + "\n")
        system = {"python": sys.version, "platform": platform.platform(), "machine": platform.machine()}
        archive.writestr("versions.json", json.dumps({**versions, **system}, indent=2) + "\n")
        archive.writestr("environment.json", json.dumps(safe_environment(environ), indent=2) + "\n")
        if effective_config is not None:
            archive.writestr("config.json", json.dumps(effective_config, indent=2, default=str) + "\n")
        archive.writestr("daemon.log", "\n".join(tail(daemon_log, log_lines)) + "\n")
        for name, lines in kiro_lines(environ, 500).items():
            archive.writestr(f"kiro-{name}", "\n".join(lines) + "\n")
        for name, text in (extra or {}).items():
            archive.writestr(name, text)
    target.expanduser().parent.mkdir(parents=True, exist_ok=True)
    target.expanduser().write_bytes(buffer.getvalue())
    return target.expanduser()


def timed(name: str, function: Callable[..., Any], logger: logging.Logger) -> Callable[..., Any]:
    """Wrap an MCP tool: log its arguments and duration at debug level, and any failure with its traceback."""

    @functools.wraps(function)
    def wrapper(*args: Any, **kwargs: Any) -> Any:
        started = time.monotonic()
        logger.debug("tool %s called with %s", name, kwargs or args)
        try:
            result = function(*args, **kwargs)
        except Exception:
            logger.exception("tool %s failed after %.0f ms", name, (time.monotonic() - started) * 1000)
            raise
        logger.debug("tool %s answered in %.0f ms", name, (time.monotonic() - started) * 1000)
        return result

    return wrapper


def lines_of(rows: Iterable[tuple[str, str, str]]) -> list[str]:
    return [f"{level:5} {name:12} {message}" for level, name, message in rows]
