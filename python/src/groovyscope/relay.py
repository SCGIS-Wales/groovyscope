"""Byte relay between this process's stdio and the daemon's Unix socket, so Kiro keeps using stdio."""

from __future__ import annotations

import asyncio
import contextlib
import logging
import sys
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import BinaryIO

CHUNK = 65536
LOG = logging.getLogger("groovyscope.relay")

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


def lost(exc: OSError) -> int:
    """Report a connection that broke mid-session; the exit code tells the client to restart the server."""
    LOG.error("connection to the daemon lost (%s); the client should restart the server", exc)
    return 1


def closed_cleanly(task: asyncio.Task[None]) -> bool:
    """True when the daemon side ended with EOF rather than an error."""
    return task.done() and not task.cancelled() and task.exception() is None


Connection = tuple[asyncio.StreamReader, asyncio.StreamWriter]


async def relay(
    socket_file: Path,
    stdin: asyncio.StreamReader,
    write_out: Write,
    close_out: Close,
    connection: Connection | None = None,
) -> int:
    """Relay until the socket closes (stdin EOF half-closes the socket first). Returns 0, or 1 when the daemon
    went away mid-session: the client then restarts the server, which is the only sound recovery. Only a failure
    to connect at all raises, so the caller can fall back to running the engine directly. ``connection`` is a
    socket already open (``run`` connects before it attaches stdin, so a failed connect loses no input)."""
    sock_reader, sock_writer = connection or await asyncio.open_unix_connection(str(socket_file))
    code = 0

    async def to_socket(data: bytes) -> None:
        sock_writer.write(data)
        await sock_writer.drain()

    async def half_close_socket() -> None:
        # The daemon may have closed first (the normal end of an LSP session): on macOS write_eof then raises
        # ENOTCONN, which is not a lost connection.
        with contextlib.suppress(OSError):
            if sock_writer.can_write_eof():
                sock_writer.write_eof()

    stdin_task = asyncio.create_task(pump(stdin, to_socket, half_close_socket))
    socket_task = asyncio.create_task(pump(sock_reader, write_out, close_out))
    try:
        await socket_task
    except OSError as exc:
        code = lost(exc)
    stdin_task.cancel()
    try:
        await stdin_task
    except asyncio.CancelledError:
        pass
    except OSError as exc:
        # A write that failed while the daemon side was still open means the connection broke; after the daemon
        # closed normally, a failing last write is just the end of the session.
        if code == 0 and not closed_cleanly(socket_task):
            code = lost(exc)
    sock_writer.close()
    with contextlib.suppress(OSError):
        await sock_writer.wait_closed()
    return code


async def run(socket_file: Path) -> int:
    """Wire the real stdio of this process to the socket. The socket is connected first: once stdin is attached to
    the loop its bytes (the client's `initialize` among them) belong to this process, and a fallback that runs the
    engine directly could not hand them over."""
    connection = await asyncio.open_unix_connection(str(socket_file))
    loop = asyncio.get_running_loop()
    stdin = asyncio.StreamReader()
    await loop.connect_read_pipe(lambda: asyncio.StreamReaderProtocol(stdin), sys.stdin.buffer)
    write_out, close_out = await output_pipe(sys.stdout.buffer)
    return await relay(socket_file, stdin, write_out, close_out, connection)


async def output_pipe(pipe: BinaryIO) -> tuple[Write, Close]:
    """Writing to ``pipe`` with back pressure: a write waits (``drain``) while the client reads slower than the
    daemon answers, so the relay's buffer stays bounded instead of holding every reply in memory."""
    loop = asyncio.get_running_loop()
    transport, protocol = await loop.connect_write_pipe(asyncio.streams.FlowControlMixin, pipe)
    writer = asyncio.StreamWriter(transport, protocol, None, loop)

    async def write_out(data: bytes) -> None:
        writer.write(data)
        await writer.drain()

    async def close_out() -> None:
        writer.close()

    return (write_out, close_out)


def main(socket_file: Path) -> int:
    return asyncio.run(run(socket_file))
