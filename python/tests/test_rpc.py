from __future__ import annotations

import json
import socketserver
import threading
from collections.abc import Iterator
from pathlib import Path

import pytest

from groovyscope import rpc


def test_encode_and_split_frame_round_trip() -> None:
    frame = rpc.encode({"jsonrpc": "2.0", "id": 1, "method": "m", "params": {"é": 1}})
    assert frame.startswith(b"Content-Length: ")
    parsed, rest = rpc.split_frame(frame + b"tail")
    assert parsed == {"jsonrpc": "2.0", "id": 1, "method": "m", "params": {"é": 1}}
    assert rest == b"tail"


def test_split_frame_waits_for_complete_data() -> None:
    frame = rpc.encode({"a": 1})
    assert rpc.split_frame(frame[:5]) == (None, frame[:5])
    assert rpc.split_frame(frame[:-1]) == (None, frame[:-1])
    with pytest.raises(ValueError, match="Content-Length"):
        rpc.split_frame(b"X-Other: 1\r\n\r\n{}")


class _Handler(socketserver.StreamRequestHandler):
    def handle(self) -> None:
        buffer = b""
        while True:
            chunk = self.request.recv(65536)
            if not chunk:
                return
            buffer += chunk
            message, buffer = rpc.split_frame(buffer)
            if message is None:
                continue
            server: _Server = self.server  # type: ignore[assignment]
            server.received.append(message)
            if "id" not in message:
                return
            # A notification arrives before the reply, which clients must skip.
            self.request.sendall(rpc.encode({"jsonrpc": "2.0", "method": "window/logMessage", "params": {}}))
            if message["method"] == "fail":
                self.request.sendall(
                    rpc.encode({"jsonrpc": "2.0", "id": message["id"], "error": {"code": -1, "message": "nope"}})
                )
            elif message["method"] == "hangup":
                return
            else:
                self.request.sendall(
                    rpc.encode({"jsonrpc": "2.0", "id": message["id"], "result": {"echo": message["params"]}})
                )
            return


class _Server(socketserver.ThreadingUnixStreamServer):
    def __init__(self, path: str) -> None:
        super().__init__(path, _Handler)
        self.received: list[dict[str, object]] = []


@pytest.fixture
def server(short_dir: Path) -> Iterator[_Server]:
    path = short_dir / "s.sock"
    srv = _Server(str(path))
    thread = threading.Thread(target=srv.serve_forever, daemon=True)
    thread.start()
    try:
        yield srv
    finally:
        srv.shutdown()
        srv.server_close()


def test_request_skips_notifications_and_returns_result(server: _Server, short_dir: Path) -> None:
    result = rpc.request(short_dir / "s.sock", "groovyscope/status", {"x": 1}, timeout=5)
    assert result == {"echo": {"x": 1}}
    assert server.received[0]["method"] == "groovyscope/status"


def test_request_errors(server: _Server, short_dir: Path) -> None:
    with pytest.raises(RuntimeError, match="nope"):
        rpc.request(short_dir / "s.sock", "fail", timeout=5)
    with pytest.raises(RuntimeError, match="closed before a reply"):
        rpc.request(short_dir / "s.sock", "hangup", timeout=5)
    with pytest.raises(OSError):
        rpc.request(short_dir / "missing.sock", "x", timeout=1)


def test_notify(server: _Server, short_dir: Path) -> None:
    rpc.notify(short_dir / "s.sock", "groovyscope/shutdownDaemon")
    deadline = 50
    while not server.received and deadline:
        threading.Event().wait(0.05)
        deadline -= 1
    assert server.received == [{"jsonrpc": "2.0", "method": "groovyscope/shutdownDaemon", "params": {}}]
    assert json.loads(rpc.encode({"a": 1})[rpc.encode({"a": 1}).index(b"{") :]) == {"a": 1}


class _SessionHandler(socketserver.StreamRequestHandler):
    """A fake engine that keeps the connection open and answers request after request."""

    def handle(self) -> None:
        buffer = b""
        server: _SessionServer = self.server  # type: ignore[assignment]
        while True:
            message, buffer = rpc.split_frame(buffer)
            if message is None:
                chunk = self.request.recv(65536)
                if not chunk:
                    return
                buffer += chunk
                continue
            server.received.append(message)
            if "id" not in message or "method" not in message:
                continue  # a notification from the client, or its answer to our reverse request
            if message["method"] == "fail":
                self.request.sendall(
                    rpc.encode({"jsonrpc": "2.0", "id": message["id"], "error": {"code": -1, "message": "nope"}})
                )
            elif message["method"] == "hangup":
                return
            else:
                # Noise a client must skip: a notification and a request from the engine, then the reply.
                self.request.sendall(rpc.encode({"jsonrpc": "2.0", "method": "window/logMessage", "params": {}}))
                self.request.sendall(
                    rpc.encode({"jsonrpc": "2.0", "id": "engine-1", "method": "client/registerCapability"})
                )
                self.request.sendall(
                    rpc.encode({"jsonrpc": "2.0", "id": message["id"], "result": {"echo": message["params"]}})
                )


class _SessionServer(socketserver.ThreadingUnixStreamServer):
    def __init__(self, path: str) -> None:
        super().__init__(path, _SessionHandler)
        self.received: list[dict[str, object]] = []


@pytest.fixture
def session_server(short_dir: Path) -> Iterator[_SessionServer]:
    srv = _SessionServer(str(short_dir / "e.sock"))
    thread = threading.Thread(target=srv.serve_forever, daemon=True)
    thread.start()
    try:
        yield srv
    finally:
        srv.shutdown()
        srv.server_close()


def _wait_for(server: _SessionServer, count: int) -> None:
    deadline = 100
    while len(server.received) < count and deadline:
        threading.Event().wait(0.05)
        deadline -= 1


def test_session_answers_many_requests_on_one_connection(session_server: _SessionServer, short_dir: Path) -> None:
    session = rpc.open_session(short_dir / "e.sock", timeout=5)
    try:
        assert rpc.call(session, "initialize", {"a": 1}) == {"echo": {"a": 1}}
        assert rpc.call(session, "groovyscope/symbols", {"query": "g"}, timeout=5) == {"echo": {"query": "g"}}
        rpc.notify_session(session, "initialized")
        _wait_for(session_server, 5)
        assert session["notifications"] == 2  # counted, never stored
    finally:
        rpc.close_session(session)
        rpc.close_session(session)  # closing twice is harmless
    ids = [m.get("id") for m in session_server.received if m.get("method") in {"initialize", "groovyscope/symbols"}]
    assert ids == [1, 2]
    answers = [m for m in session_server.received if m.get("id") == "engine-1"]
    assert [a["error"]["code"] for a in answers] == [-32601, -32601]
    assert {"jsonrpc": "2.0", "method": "initialized", "params": {}} in session_server.received


def test_session_errors(session_server: _SessionServer, short_dir: Path) -> None:
    session = rpc.open_session(short_dir / "e.sock", timeout=5)
    try:
        with pytest.raises(RuntimeError, match="fail failed: nope"):
            rpc.call(session, "fail")
        with pytest.raises(RuntimeError, match="engine closed the session"):
            rpc.call(session, "hangup")
    finally:
        rpc.close_session(session)
