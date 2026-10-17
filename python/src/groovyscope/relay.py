"""Byte relay between this process's stdio and the daemon's Unix socket, so Kiro keeps using stdio."""

from __future__ import annotations

import asyncio
import contextlib
import sys
from collections.abc import Awaitable, Callable
from pathlib import Path

CHUNK = 65536

Write = Callable[[bytes], Awaitable[None]]
Close = Callable[[], Awaitable[None]]


async def pump(reader: asyncio.StreamReader, write: Write, on_eof: Close) -> None:
    """Copy bytes from ``reader`` to ``write`` until EOF, then run ``on_eof``."""
    try:
        while True:
            data = await reader.read(CHUNK)
            if not data:
                break
            await write(data)
    finally:
        await on_eof()


async def relay(socket_file: Path, stdin: asyncio.StreamReader, write_out: Write, close_out: Close) -> int:
    """Relay until the socket closes (stdin EOF half-closes the socket first). Returns 0."""
    sock_reader, sock_writer = await asyncio.open_unix_connection(str(socket_file))

    async def to_socket(data: bytes) -> None:
        sock_writer.write(data)
        await sock_writer.drain()

    async def half_close_socket() -> None:
        if sock_writer.can_write_eof():
            sock_writer.write_eof()

    stdin_task = asyncio.create_task(pump(stdin, to_socket, half_close_socket))
    socket_task = asyncio.create_task(pump(sock_reader, write_out, close_out))
    await socket_task
    stdin_task.cancel()
    with contextlib.suppress(asyncio.CancelledError):
        await stdin_task
    sock_writer.close()
    with contextlib.suppress(OSError):
        await sock_writer.wait_closed()
    return 0


async def run(socket_file: Path) -> int:
    """Wire the real stdio of this process to the socket."""
    loop = asyncio.get_running_loop()
    stdin = asyncio.StreamReader()
    await loop.connect_read_pipe(lambda: asyncio.StreamReaderProtocol(stdin), sys.stdin.buffer)
    transport, _ = await loop.connect_write_pipe(asyncio.Protocol, sys.stdout.buffer)

    async def write_out(data: bytes) -> None:
        transport.write(data)

    async def close_out() -> None:
        transport.close()

    return await relay(socket_file, stdin, write_out, close_out)


def main(socket_file: Path) -> int:
    return asyncio.run(run(socket_file))
