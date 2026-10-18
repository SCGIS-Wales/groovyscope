"""A minimal JSON-RPC client over a Unix domain socket, with LSP's Content-Length framing."""

from __future__ import annotations

import contextlib
import json
import socket
import threading
from concurrent.futures import Future
from pathlib import Path
from typing import Any, TypedDict

HEADER_END = b"\r\n\r\n"
DEFAULT_TIMEOUT = 10.0
MAX_FRAME_BYTES = 512 * 1024 * 1024  # a reply larger than this is refused rather than buffered
MAX_HEADER_BYTES = 8192


def encode(message: dict[str, Any]) -> bytes:
    """Frame one message: ``Content-Length`` header, blank line, UTF-8 JSON body."""
    body = json.dumps(message, separators=(",", ":")).encode("utf-8")
    return f"Content-Length: {len(body)}\r\n\r\n".encode("ascii") + body


def split_frame(buffer: bytes) -> tuple[dict[str, Any] | None, bytes]:
    """Return ``(message, rest)`` when ``buffer`` holds a complete frame, else ``(None, buffer)``."""
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
    body: dict[str, Any] = json.loads(buffer[start : start + length].decode("utf-8"))
    return (body, buffer[start + length :])


def _connect(socket_path: Path, timeout: float) -> socket.socket:
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.settimeout(timeout)
    sock.connect(str(socket_path))
    return sock


def request(socket_path: Path, method: str, params: Any = None, timeout: float = DEFAULT_TIMEOUT) -> Any:
    """Send one request and return its result; notifications arriving first are skipped.

    Raises ``OSError`` when the socket is unreachable or the connection drops (``ConnectionError``),
    ``TimeoutError`` on silence, and ``RuntimeError`` when the server answers with an error.
    """
    message = {"jsonrpc": "2.0", "id": 1, "method": method, "params": {} if params is None else params}
    with _connect(socket_path, timeout) as sock:
        sock.sendall(encode(message))
        buffer = b""
        while True:
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
                        error = parsed["error"]
                        msg = f"{method} failed: {error.get('message', error)}"
                        raise RuntimeError(msg)
                    return parsed.get("result")


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
    """Send a request on the session and wait for its reply; notifications in between are dropped."""
    sock: socket.socket = session["sock"]
    if timeout is not None:
        sock.settimeout(timeout)
    request_id = session["next_id"]
    session["next_id"] = request_id + 1
    sock.sendall(
        encode({"jsonrpc": "2.0", "id": request_id, "method": method, "params": {} if params is None else params})
    )
    while True:
        message = _read_frame(session)
        if message.get("id") == request_id and ("result" in message or "error" in message):
            if "error" in message:
                error = message["error"]
                msg = f"{method} failed: {error.get('message', error)}"
                raise RuntimeError(msg)
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
CHANNEL_POLL_SECONDS = 0.5


class Channel(TypedDict):
    """One engine connection shared by concurrent callers: a reader thread matches replies to requests by id and
    completes each caller's future; writes are serialised by ``lock`` so frames never interleave."""

    sock: socket.socket
    lock: threading.Lock
    pending: dict[int, Future[Any]]
    next_id: int
    reader: threading.Thread
    closed: threading.Event
    failure: list[BaseException]


def open_channel(socket_path: Path, timeout: float = DEFAULT_TIMEOUT) -> Channel:
    """Connect once and start the reader thread."""
    sock = _connect(socket_path, timeout)
    sock.settimeout(CHANNEL_POLL_SECONDS)
    channel: Channel = {
        "sock": sock,
        "lock": threading.Lock(),
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
            try:
                chunk = sock.recv(65536)
            except TimeoutError:
                continue
            if not chunk:
                msg = "engine closed the connection"
                raise ConnectionError(msg)
            buffer += chunk
            while True:
                message, buffer = split_frame(buffer)
                if message is None:
                    break
                _dispatch(channel, message)
    except (OSError, ValueError) as exc:  # the socket died or the stream is out of frame
        channel["failure"].append(exc)
    finally:
        channel["closed"].set()
        with channel["lock"]:
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
    with channel["lock"]:
        future = channel["pending"].pop(message.get("id"), None)  # type: ignore[arg-type]
    if future is None:
        return  # a reply to a request whose caller gave up (and sent a cancel) or an unknown id
    if "error" in message:
        error = message["error"]
        future.set_exception(RuntimeError(f"{error.get('message', error)}"))
    else:
        future.set_result(message.get("result"))


def _send(channel: Channel, message: dict[str, Any]) -> None:
    with channel["lock"]:
        channel["sock"].sendall(encode(message))


def channel_alive(channel: Channel) -> bool:
    return not channel["closed"].is_set()


def channel_submit(channel: Channel, method: str, params: Any = None) -> tuple[int, Future[Any]]:
    """Send a request and return its id with the future its reply completes. Raises ``OSError`` when the
    connection is gone."""
    if channel["closed"].is_set():
        failure = channel["failure"][0] if channel["failure"] else ConnectionError("engine connection closed")
        raise failure if isinstance(failure, OSError) else ConnectionError(str(failure))
    future: Future[Any] = Future()
    with channel["lock"]:
        request_id = channel["next_id"]
        channel["next_id"] = request_id + 1
        channel["pending"][request_id] = future
        message = {"jsonrpc": "2.0", "id": request_id, "method": method, "params": {} if params is None else params}
        try:
            channel["sock"].sendall(encode(message))
        except OSError:
            channel["pending"].pop(request_id, None)
            raise
    return (request_id, future)


def channel_cancel(channel: Channel, request_id: int) -> None:
    """Tell the engine the caller no longer waits for ``request_id``; the reply, if any, is dropped."""
    with channel["lock"]:
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
