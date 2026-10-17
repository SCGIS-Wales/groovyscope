from __future__ import annotations

import asyncio
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

    async def write_out(data: bytes) -> None:
        received.append(data)

    async def close_out() -> None:
        out_closed.set()

    task = asyncio.create_task(relay.relay(path, stdin, write_out, close_out))
    stdin.feed_data(b"hello")
    await asyncio.sleep(0.1)
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
