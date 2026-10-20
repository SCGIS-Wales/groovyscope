from __future__ import annotations

import json
import socket
import socketserver
import threading
from collections.abc import Iterator
from concurrent.futures import Future
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
            elif message["method"] == "trickle":
                # notifications without end: only a total deadline stops a client waiting for the reply
                note = rpc.encode({"jsonrpc": "2.0", "method": "window/logMessage", "params": {}})
                try:
                    while True:
                        self.request.sendall(note)
                except OSError:
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
    with pytest.raises(ConnectionError, match="closed before a reply"):
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
            elif message["method"] == "hang":
                continue  # never answered: what a client's timeout and cancel are for
            elif message["method"] == "exiting":
                self.request.sendall(
                    rpc.encode(
                        {
                            "jsonrpc": "2.0",
                            "id": message["id"],
                            "error": {"code": rpc.DAEMON_EXITING_CODE, "message": "daemon is exiting; reconnect"},
                        }
                    )
                )
            elif message["method"] == "array":
                body = b"[1, 2]"
                self.request.sendall(b"Content-Length: %d\r\n\r\n" % len(body) + body)
                self.request.sendall(rpc.encode({"jsonrpc": "2.0", "id": message["id"], "result": "after"}))
            elif message["method"] == "slow":
                threading.Event().wait(0.3)
                self.request.sendall(rpc.encode({"jsonrpc": "2.0", "id": message["id"], "result": "late"}))
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


def test_a_channel_answers_many_requests_and_refuses_engine_requests(
    session_server: _SessionServer, short_dir: Path
) -> None:
    channel = rpc.open_channel(short_dir / "e.sock", timeout=5)
    try:
        assert rpc.channel_call(channel, "initialize", {"a": 1}, timeout=5) == {"echo": {"a": 1}}
        assert rpc.channel_call(channel, "groovyscope/symbols", {"query": "g"}, timeout=5) == {"echo": {"query": "g"}}
        _wait_for(session_server, 4)
    finally:
        rpc.close_channel(channel)
        rpc.close_channel(channel)  # closing twice is harmless
    answers = [m for m in session_server.received if m.get("id") == "engine-1"]
    assert [a["error"]["code"] for a in answers] == [-32601, -32601]


def test_oversized_frames_are_refused() -> None:
    with pytest.raises(ValueError, match="exceeds"):
        rpc.split_frame(f"Content-Length: {rpc.MAX_FRAME_BYTES + 1}\r\n\r\n".encode())
    with pytest.raises(ValueError, match="header too long"):
        rpc.split_frame(b"x" * (rpc.MAX_HEADER_BYTES + 1))


def test_channel_answers_concurrent_requests_by_id(session_server: _SessionServer, short_dir: Path) -> None:
    channel = rpc.open_channel(short_dir / "e.sock", timeout=5)
    try:
        results: dict[str, object] = {}

        def one(name: str, method: str) -> None:
            results[name] = rpc.channel_call(channel, method, {"who": name}, timeout=10)

        threads = [
            threading.Thread(target=one, args=("slowpoke", "slow")),
            threading.Thread(target=one, args=("quick", "groovyscope/status")),
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(15)
        assert results["quick"] == {"echo": {"who": "quick"}}
        assert results["slowpoke"] == "late"
        assert rpc.channel_alive(channel)
        with pytest.raises(RuntimeError, match="fail failed: nope"):
            rpc.channel_call(channel, "fail", {}, timeout=5)
    finally:
        rpc.close_channel(channel)
    assert not rpc.channel_alive(channel)
    with pytest.raises(OSError):
        rpc.channel_submit(channel, "groovyscope/status", {})


def test_channel_timeout_cancels_the_request_on_the_engine(session_server: _SessionServer, short_dir: Path) -> None:
    channel = rpc.open_channel(short_dir / "e.sock", timeout=5)
    try:
        with pytest.raises(TimeoutError, match=r"hang did not answer within 0\.3s"):
            rpc.channel_call(channel, "hang", {"n": 1}, timeout=0.3)
        _wait_for(session_server, 2)
        cancel = [m for m in session_server.received if m.get("method") == rpc.CANCEL_METHOD]
        assert cancel and cancel[0]["params"] == {"id": 1}
        assert channel["pending"] == {}
        # The channel is still usable after a timed-out request.
        assert rpc.channel_call(channel, "groovyscope/status", {}, timeout=5) == {"echo": {}}
    finally:
        rpc.close_channel(channel)


def test_channel_fails_every_waiter_when_the_engine_hangs_up(session_server: _SessionServer, short_dir: Path) -> None:
    channel = rpc.open_channel(short_dir / "e.sock", timeout=5)
    with pytest.raises(OSError):
        rpc.channel_call(channel, "hangup", {}, timeout=5)
    assert not rpc.channel_alive(channel)
    rpc.close_channel(channel)


def test_split_frame_refuses_a_body_that_is_not_an_object() -> None:
    body = b"[1, 2]"
    with pytest.raises(ValueError, match="not a JSON object"):
        rpc.split_frame(b"Content-Length: %d\r\n\r\n" % len(body) + body)


def test_request_has_one_deadline_however_many_notifications_arrive(server: _Server, short_dir: Path) -> None:
    with pytest.raises(TimeoutError, match=r"trickle did not answer within 0\.3s"):
        rpc.request(short_dir / "s.sock", "trickle", {}, timeout=0.3)


def test_a_frame_that_is_not_an_object_does_not_stop_the_reader(
    session_server: _SessionServer, short_dir: Path
) -> None:
    channel = rpc.open_channel(short_dir / "e.sock", timeout=5)
    try:
        assert rpc.channel_call(channel, "array", {}, timeout=5) == "after"
        assert rpc.channel_alive(channel)
    finally:
        rpc.close_channel(channel)


def test_a_daemon_that_is_exiting_is_a_connection_error(session_server: _SessionServer, short_dir: Path) -> None:
    channel = rpc.open_channel(short_dir / "e.sock", timeout=5)
    try:
        with pytest.raises(rpc.DaemonExitingError, match="daemon is exiting"):
            rpc.channel_call(channel, "exiting", {}, timeout=5)
        assert issubclass(rpc.DaemonExitingError, ConnectionError)
    finally:
        rpc.close_channel(channel)


def test_a_write_that_cannot_finish_breaks_the_channel_instead_of_leaving_half_a_frame(
    short_dir: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(rpc, "CHANNEL_WRITE_SECONDS", 0.2)
    listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    listener.bind(str(short_dir / "deaf.sock"))
    listener.listen(1)
    try:
        channel = rpc.open_channel(short_dir / "deaf.sock", timeout=5)
        with pytest.raises(rpc.ChannelBrokenError):
            rpc.channel_submit(channel, "groovyscope/check", {"blob": "x" * 8_000_000})
        assert not rpc.channel_alive(channel)
        assert issubclass(rpc.ChannelBrokenError, ConnectionError)
        rpc.close_channel(channel)
    finally:
        listener.close()


def test_a_large_frame_reaches_a_reader_that_takes_it_in_pieces(
    session_server: _SessionServer, short_dir: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(rpc, "CHANNEL_POLL_SECONDS", 0.01)
    channel = rpc.open_channel(short_dir / "e.sock", timeout=5)
    try:
        blob = "y" * 1_000_000
        assert rpc.channel_call(channel, "groovyscope/status", {"blob": blob}, timeout=30) == {"echo": {"blob": blob}}
        assert rpc.channel_alive(channel)
    finally:
        rpc.close_channel(channel)


def test_the_reader_completes_a_reply_while_a_long_write_holds_the_write_lock() -> None:
    left, right = socket.socketpair()
    channel: rpc.Channel = {
        "sock": left,
        "lock": threading.Lock(),
        "pending_lock": threading.Lock(),
        "pending": {},
        "next_id": 2,
        "reader": threading.Thread(),
        "closed": threading.Event(),
        "failure": [],
    }
    waiting: Future[object] = Future()
    channel["pending"][1] = waiting
    try:
        with channel["lock"]:  # a large frame is being written
            reader = threading.Thread(target=rpc._dispatch, args=(channel, {"jsonrpc": "2.0", "id": 1, "result": 5}))
            reader.start()
            reader.join(5)
            assert waiting.result(0) == 5
    finally:
        left.close()
        right.close()
