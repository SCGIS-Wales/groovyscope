"""A minimal JSON-RPC client over a Unix domain socket, with LSP's Content-Length framing."""

from __future__ import annotations

import contextlib
import json
import socket
from pathlib import Path
from typing import Any

HEADER_END = b"\r\n\r\n"
DEFAULT_TIMEOUT = 10.0


def encode(message: dict[str, Any]) -> bytes:
    """Frame one message: ``Content-Length`` header, blank line, UTF-8 JSON body."""
    body = json.dumps(message, separators=(",", ":")).encode("utf-8")
    return f"Content-Length: {len(body)}\r\n\r\n".encode("ascii") + body


def split_frame(buffer: bytes) -> tuple[dict[str, Any] | None, bytes]:
    """Return ``(message, rest)`` when ``buffer`` holds a complete frame, else ``(None, buffer)``."""
    end = buffer.find(HEADER_END)
    if end < 0:
        return (None, buffer)
    length = -1
    for line in buffer[:end].decode("ascii", "replace").split("\r\n"):
        name, _, value = line.partition(":")
        if name.strip().lower() == "content-length":
            length = int(value.strip())
    if length < 0:
        msg = "frame without Content-Length"
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

    Raises ``OSError`` when the socket is unreachable, ``TimeoutError`` on silence, and ``RuntimeError``
    when the server answers with an error.
    """
    message = {"jsonrpc": "2.0", "id": 1, "method": method, "params": {} if params is None else params}
    with _connect(socket_path, timeout) as sock:
        sock.sendall(encode(message))
        buffer = b""
        while True:
            chunk = sock.recv(65536)
            if not chunk:
                msg = f"connection closed before a reply to {method}"
                raise RuntimeError(msg)
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
            raise RuntimeError(msg)
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
