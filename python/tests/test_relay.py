from __future__ import annotations

import asyncio
import logging
import os
from pathlib import Path

import pytest

from groovyscope import relay


async def _echo_server(path: Path, closed: asyncio.Event) -> asyncio.AbstractServer:
    async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            writer.write(b"echo:" + data)
            await writer.drain()
        writer.close()
        closed.set()

    return await asyncio.start_unix_server(handle, path=str(path))


@pytest.mark.asyncio
async def test_relay_copies_both_ways_and_ends_when_the_socket_closes(short_dir: Path) -> None:
    path = short_dir / "r.sock"
    closed = asyncio.Event()
    server = await _echo_server(path, closed)
    stdin = asyncio.StreamReader()
    received: list[bytes] = []
    out_closed = asyncio.Event()

    echoed = asyncio.Event()

    async def write_out(data: bytes) -> None:
        received.append(data)
        echoed.set()

    async def close_out() -> None:
        out_closed.set()

    task = asyncio.create_task(relay.relay(path, stdin, write_out, close_out))
    stdin.feed_data(b"hello")
    await asyncio.wait_for(echoed.wait(), 10)  # the echo came back: the request was relayed before EOF
    stdin.feed_eof()
    code = await asyncio.wait_for(task, 10)
    server.close()
    await server.wait_closed()
    assert code == 0
    assert b"".join(received) == b"echo:hello"
    assert out_closed.is_set()
    assert closed.is_set()


@pytest.mark.asyncio
async def test_pump_runs_on_eof_even_after_an_error() -> None:
    reader = asyncio.StreamReader()
    reader.feed_data(b"x")
    reader.feed_eof()
    done = asyncio.Event()

    async def write(_data: bytes) -> None:
        msg = "sink broke"
        raise OSError(msg)

    async def on_eof() -> None:
        done.set()

    with pytest.raises(OSError, match="sink broke"):
        await relay.pump(reader, write, on_eof)
    assert done.is_set()


@pytest.mark.asyncio
async def test_relay_uses_a_connection_opened_before_stdin_is_touched(short_dir: Path) -> None:
    path = short_dir / "c.sock"
    closed = asyncio.Event()
    server = await _echo_server(path, closed)
    connection = await asyncio.open_unix_connection(str(path))
    stdin = asyncio.StreamReader()
    received: list[bytes] = []

    echoed = asyncio.Event()

    async def write_out(data: bytes) -> None:
        received.append(data)
        echoed.set()

    async def close_out() -> None:
        pass

    task = asyncio.create_task(relay.relay(path, stdin, write_out, close_out, connection))
    stdin.feed_data(b"init")
    await asyncio.wait_for(echoed.wait(), 10)
    stdin.feed_eof()
    assert await asyncio.wait_for(task, 10) == 0
    assert b"".join(received) == b"echo:init"
    server.close()
    await server.wait_closed()
    with pytest.raises(OSError):
        await relay.run(short_dir / "nobody.sock")


@pytest.mark.asyncio
async def test_a_slow_client_holds_the_relay_back_instead_of_filling_memory() -> None:
    read_end, write_end = os.pipe()
    with os.fdopen(write_end, "wb", buffering=0) as pipe:
        write_out, close_out = await relay.output_pipe(pipe)
        chunk = b"x" * relay.CHUNK

        async def flood() -> None:
            for _ in range(64):  # 4 MiB the client never reads
                await write_out(chunk)

        task = asyncio.create_task(flood())
        done, _ = await asyncio.wait([task], timeout=0.5)
        assert not done  # the writer waits for the reader instead of buffering it all
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        await close_out()
    os.close(read_end)


def test_a_lost_connection_is_logged(caplog: pytest.LogCaptureFixture) -> None:
    with caplog.at_level(logging.ERROR, logger="groovyscope.relay"):
        assert relay.lost(ConnectionResetError("reset")) == 1
    assert "connection to the daemon lost (reset)" in caplog.text
