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
