"""A minimal JSON-RPC client over a Unix domain socket, with LSP's Content-Length framing."""

from __future__ import annotations

import contextlib
import json
import logging
import select
import socket
import threading
import time
from concurrent.futures import Future
from pathlib import Path
from typing import Any, TypedDict

HEADER_END = b"\r\n\r\n"
DEFAULT_TIMEOUT = 10.0
MAX_FRAME_BYTES = 512 * 1024 * 1024  # a reply larger than this is refused rather than buffered
MAX_HEADER_BYTES = 8192
DAEMON_EXITING_CODE = -32099  # the engine answers this, "daemon is exiting ...", while it shuts down: reconnect
DAEMON_EXITING_PREFIX = "daemon is exiting"

LOG = logging.getLogger("groovyscope.rpc")


class ChannelBrokenError(ConnectionError):
    """A write could not finish: the stream may hold half a frame, so the channel is closed and must be rebuilt."""


class DaemonExitingError(ConnectionError):
    """The daemon answered that it is shutting down; a fresh connection (to a new daemon) can answer."""


def error_from(method: str, error: Any) -> Exception:
    """The exception for a JSON-RPC error answer: ``DaemonExitingError`` for a daemon that is shutting down
    (callers reconnect on ``ConnectionError``), ``RuntimeError`` for anything else."""
    message = str(error.get("message", error)) if isinstance(error, dict) else str(error)
    if (
        isinstance(error, dict)
        and error.get("code") == DAEMON_EXITING_CODE
        and message.startswith(DAEMON_EXITING_PREFIX)
    ):
        return DaemonExitingError(f"{method}: {message}" if method else message)
    return RuntimeError(f"{method} failed: {message}" if method else message)


def encode(message: dict[str, Any]) -> bytes:
    """Frame one message: ``Content-Length`` header, blank line, UTF-8 JSON body."""
    body = json.dumps(message, separators=(",", ":")).encode("utf-8")
    return f"Content-Length: {len(body)}\r\n\r\n".encode("ascii") + body


def split_frame(buffer: bytes) -> tuple[dict[str, Any] | None, bytes]:
    """Return ``(message, rest)`` when ``buffer`` holds a complete frame, else ``(None, buffer)``. A body that is
    not a JSON object raises ``ValueError``."""
    body, rest = split_raw(buffer)
    if body is None:
        return (None, rest)
    if not isinstance(body, dict):
        msg = f"frame body is not a JSON object ({type(body).__name__})"
        raise ValueError(msg)
    return (body, rest)


def split_raw(buffer: bytes) -> tuple[Any, bytes]:
    """``(body, rest)`` for a complete frame whatever JSON value it holds, else ``(None, buffer)``."""
    end = buffer.find(HEADER_END)
    if end < 0:
        if len(buffer) > MAX_HEADER_BYTES:
            msg = "frame header too long"
            raise ValueError(msg)
        return (None, buffer)
    length = -1
    for line in buffer[:end].decode("ascii", "replace").split("\r\n"):
        name, _, value = line.partition(":")
        if name.strip().lower() == "content-length":
            length = int(value.strip())
    if length < 0:
        msg = "frame without Content-Length"
        raise ValueError(msg)
    if length > MAX_FRAME_BYTES:
        msg = f"frame of {length} bytes exceeds the {MAX_FRAME_BYTES}-byte limit"
        raise ValueError(msg)
    start = end + len(HEADER_END)
    if len(buffer) < start + length:
        return (None, buffer)
    return (json.loads(buffer[start : start + length].decode("utf-8")), buffer[start + length :])


def _connect(socket_path: Path, timeout: float) -> socket.socket:
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.settimeout(timeout)
    sock.connect(str(socket_path))
    return sock


def request(socket_path: Path, method: str, params: Any = None, timeout: float = DEFAULT_TIMEOUT) -> Any:
    """Send one request and return its result; notifications arriving first are skipped.

    Raises ``OSError`` when the socket is unreachable or the connection drops (``ConnectionError``),
    ``TimeoutError`` when no reply arrives within ``timeout`` (one deadline, however many notifications come
    first), and ``RuntimeError`` when the server answers with an error.
    """
    message = {"jsonrpc": "2.0", "id": 1, "method": method, "params": {} if params is None else params}
    deadline = time.monotonic() + timeout
    with _connect(socket_path, timeout) as sock:
        sock.sendall(encode(message))
        buffer = b""
        while True:
            _until(sock, deadline, method, timeout)
            chunk = sock.recv(65536)
            if not chunk:
                msg = f"connection closed before a reply to {method}"
                raise ConnectionError(msg)
            buffer += chunk
            while True:
                parsed, buffer = split_frame(buffer)
                if parsed is None:
                    break
                if parsed.get("id") == 1:
                    if "error" in parsed:
                        raise error_from(method, parsed["error"])
                    return parsed.get("result")


def _until(sock: socket.socket, deadline: float, method: str, timeout: float) -> None:
    """Narrows the socket's timeout to what is left before ``deadline``; past it, ``TimeoutError``."""
    left = deadline - time.monotonic()
    if left <= 0:
        msg = f"{method} did not answer within {timeout}s"
        raise TimeoutError(msg)
    sock.settimeout(left)


def notify(socket_path: Path, method: str, params: Any = None, timeout: float = DEFAULT_TIMEOUT) -> None:
    """Send one notification and close."""
    message = {"jsonrpc": "2.0", "method": method, "params": {} if params is None else params}
    with _connect(socket_path, timeout) as sock:
        sock.sendall(encode(message))


# ---- persistent sessions (one connection, many requests; used by the MCP server) ----

Session = dict[str, Any]


def open_session(socket_path: Path, timeout: float = DEFAULT_TIMEOUT) -> Session:
    """Connect once; the returned mapping carries the socket, the read buffer and the next request id."""
    return {"sock": _connect(socket_path, timeout), "buffer": b"", "next_id": 1, "notifications": 0}


def _read_frame(session: Session) -> dict[str, Any]:
    sock: socket.socket = session["sock"]
    while True:
        parsed, session["buffer"] = split_frame(session["buffer"])
        if parsed is not None:
            return parsed
        chunk = sock.recv(65536)
        if not chunk:
            msg = "engine closed the session"
            raise ConnectionError(msg)
        session["buffer"] += chunk


def call(session: Session, method: str, params: Any = None, timeout: float | None = None) -> Any:
    """Send a request on the session and wait for its reply (within ``timeout`` in all); notifications in between
    are dropped."""
    sock: socket.socket = session["sock"]
    deadline = None if timeout is None else time.monotonic() + timeout
    if timeout is not None:
        sock.settimeout(timeout)
    request_id = session["next_id"]
    session["next_id"] = request_id + 1
    sock.sendall(
        encode({"jsonrpc": "2.0", "id": request_id, "method": method, "params": {} if params is None else params})
    )
    while True:
        if deadline is not None and timeout is not None:
            _until(sock, deadline, method, timeout)
        message = _read_frame(session)
        if message.get("id") == request_id and ("result" in message or "error" in message):
            if "error" in message:
                raise error_from(method, message["error"])
            return message.get("result")
        if "method" in message and "id" in message:
            # A request from the engine (none are expected): answer with an error so it does not hang.
            sock.sendall(
                encode({"jsonrpc": "2.0", "id": message["id"], "error": {"code": -32601, "message": "unsupported"}})
            )
        elif "method" in message:
            session["notifications"] += 1  # counted, not kept: a long session must not grow without bound


def notify_session(session: Session, method: str, params: Any = None) -> None:
    sock: socket.socket = session["sock"]
    sock.sendall(encode({"jsonrpc": "2.0", "method": method, "params": {} if params is None else params}))


def close_session(session: Session) -> None:
    sock: socket.socket = session["sock"]
    with contextlib.suppress(OSError):
        sock.close()


# ---- channels (one connection, many requests in flight; used by the MCP server) ----

CANCEL_METHOD = "$/cancelRequest"
CHANNEL_POLL_SECONDS = 0.5  # how often the reader looks at the closed flag while the engine is silent
CHANNEL_WRITE_SECONDS = 30.0  # a frame not fully written by then breaks the channel (the stream may hold half)


class Channel(TypedDict):
    """One engine connection shared by concurrent callers: a reader thread matches replies to requests by id and
    completes each caller's future; writes are serialised by ``lock`` so frames never interleave, and ``pending``
    and ``next_id`` have their own ``pending_lock``, so the reader never waits behind a long write."""

    sock: socket.socket
    lock: threading.Lock
    pending_lock: threading.Lock
    pending: dict[int, Future[Any]]
    next_id: int
    reader: threading.Thread
    closed: threading.Event
    failure: list[BaseException]


def open_channel(socket_path: Path, timeout: float = DEFAULT_TIMEOUT) -> Channel:
    """Connect once and start the reader thread."""
    sock = _connect(socket_path, timeout)
    # Writes block up to CHANNEL_WRITE_SECONDS; the reader polls with select, so the two never share a timeout.
    sock.settimeout(CHANNEL_WRITE_SECONDS)
    channel: Channel = {
        "sock": sock,
        "lock": threading.Lock(),
        "pending_lock": threading.Lock(),
        "pending": {},
        "next_id": 1,
        "reader": threading.Thread(name="groovyscope-rpc-reader", daemon=True),
        "closed": threading.Event(),
        "failure": [],
    }
    reader = threading.Thread(target=_read_loop, args=(channel,), name="groovyscope-rpc-reader", daemon=True)
    channel["reader"] = reader
    reader.start()
    return channel


def _read_loop(channel: Channel) -> None:
    sock = channel["sock"]
    buffer = b""
    try:
        while not channel["closed"].is_set():
            readable, _, _ = select.select([sock], [], [], CHANNEL_POLL_SECONDS)
            if not readable:
                continue
            chunk = sock.recv(65536)
            if not chunk:
                msg = "engine closed the connection"
                raise ConnectionError(msg)
            buffer += chunk
            while True:
                message, buffer = split_raw(buffer)
                if message is None:
                    break
                if isinstance(message, dict):
                    _dispatch(channel, message)
                else:  # a complete frame that is not an object: skipped, the stream is still in step
                    LOG.warning("engine sent a frame that is not a JSON object; skipped")
    except (OSError, ValueError) as exc:  # the socket died or the stream is out of frame
        channel["failure"].append(exc)
    finally:
        channel["closed"].set()
        with channel["pending_lock"]:
            waiting = list(channel["pending"].values())
            channel["pending"].clear()
        failure = channel["failure"][0] if channel["failure"] else ConnectionError("engine connection closed")
        for future in waiting:
            future.set_exception(failure)


def _dispatch(channel: Channel, message: dict[str, Any]) -> None:
    if "method" in message and "id" in message:
        # A request from the engine (none are expected): answer with an error so it does not hang.
        _send(channel, {"jsonrpc": "2.0", "id": message["id"], "error": {"code": -32601, "message": "unsupported"}})
        return
    if "method" in message:
        return  # a notification: nothing waits for it
    with channel["pending_lock"]:
        future = channel["pending"].pop(message.get("id"), None)  # type: ignore[arg-type]
    if future is None:
        return  # a reply to a request whose caller gave up (and sent a cancel) or an unknown id
    if "error" in message:
        future.set_exception(error_from("", message["error"]))
    else:
        future.set_result(message.get("result"))


def _send(channel: Channel, message: dict[str, Any]) -> None:
    with channel["lock"]:
        _write(channel, encode(message))


def _write(channel: Channel, frame: bytes) -> None:
    """Writes one whole frame (the caller holds the lock). A failure part-way may leave half a frame on the
    stream, so the channel is closed and ``ChannelBrokenError`` raised: the caller reconnects."""
    try:
        channel["sock"].sendall(frame)
    except OSError as exc:
        close_channel(channel)
        msg = f"could not write to the engine ({exc}); the channel is closed"
        raise ChannelBrokenError(msg) from exc


def channel_alive(channel: Channel) -> bool:
    return not channel["closed"].is_set()


def channel_submit(channel: Channel, method: str, params: Any = None) -> tuple[int, Future[Any]]:
    """Send a request and return its id with the future its reply completes. Raises ``OSError`` when the
    connection is gone."""
    if channel["closed"].is_set():
        failure = channel["failure"][0] if channel["failure"] else ConnectionError("engine connection closed")
        raise failure if isinstance(failure, OSError) else ConnectionError(str(failure))
    future: Future[Any] = Future()
    with channel["pending_lock"]:
        request_id = channel["next_id"]
        channel["next_id"] = request_id + 1
        channel["pending"][request_id] = future
    message = {"jsonrpc": "2.0", "id": request_id, "method": method, "params": {} if params is None else params}
    try:
        with channel["lock"]:
            _write(channel, encode(message))
    except OSError:
        with channel["pending_lock"]:
            channel["pending"].pop(request_id, None)
        raise
    return (request_id, future)


def channel_cancel(channel: Channel, request_id: int) -> None:
    """Tell the engine the caller no longer waits for ``request_id``; the reply, if any, is dropped."""
    with channel["pending_lock"]:
        channel["pending"].pop(request_id, None)
    with contextlib.suppress(OSError):
        _send(channel, {"jsonrpc": "2.0", "method": CANCEL_METHOD, "params": {"id": request_id}})


def channel_call(channel: Channel, method: str, params: Any = None, timeout: float | None = None) -> Any:
    """Send one request and wait for its reply. On ``timeout`` the request is cancelled on the engine and
    ``TimeoutError`` is raised; ``RuntimeError`` carries an error answer; ``OSError`` a dead connection."""
    request_id, future = channel_submit(channel, method, params)
    try:
        return future.result(timeout)
    except TimeoutError:
        channel_cancel(channel, request_id)
        msg = f"{method} did not answer within {timeout}s"
        raise TimeoutError(msg) from None
    except RuntimeError as exc:
        msg = f"{method} failed: {exc}"
        raise RuntimeError(msg) from None


def close_channel(channel: Channel) -> None:
    channel["closed"].set()
    with contextlib.suppress(OSError):
        channel["sock"].shutdown(socket.SHUT_RDWR)
    with contextlib.suppress(OSError):
        channel["sock"].close()
