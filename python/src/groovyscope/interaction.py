"""What a tool can ask of the person behind the MCP client while it runs: a confirmation before it writes
(MCP elicitation) and a progress note while it waits.

The SDK runs each synchronous tool on a worker thread and hands the tool a request context. The response wrapper
binds that context to the worker thread for the length of the call (:func:`bound`); the functions here read it
back and reach into the event loop with ``anyio.from_thread.run``. Outside a tool call, or with a client that did
not declare the elicitation capability, nothing is asked: :func:`confirm` answers ``unsupported`` and the tool
keeps the dry-run convention (``apply`` decides), and :func:`progress` does nothing.
"""

from __future__ import annotations

import logging
import threading
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from typing import Any

import anyio.from_thread
from pydantic import Field, create_model

ACCEPTED = "accepted"
DECLINED = "declined"
CANCELLED = "cancelled"
UNSUPPORTED = "unsupported"
NOT_OFFERED = "not offered"  # asked for, but the client declared no elicitation: its own approval is the only gate
NOT_OFFERED_NOTE = (
    "this client offers no confirmations (MCP elicitation), so its own tool approval was the only check before "
    "the write"
)
POLICY_AUTO = "auto"  # ask through elicitation when the client supports it
POLICY_NEVER = "never"  # never ask; the client's own tool approval is the only gate
LOG = logging.getLogger("groovyscope.interaction")

# The answer the client collects: one boolean, which every elicitation form can render.
CONFIRM_SCHEMA: Any = create_model(
    "ConfirmWrite", apply=(bool, Field(default=True, description="Apply these changes to the workspace"))
)
_LOCAL = threading.local()
Runner = Callable[..., Any]


@contextmanager
def bound(context: Any) -> Iterator[None]:
    """Makes ``context`` (the SDK's request context, or None) this worker thread's context until the block ends."""
    previous = getattr(_LOCAL, "context", None)
    _LOCAL.context = context
    try:
        yield
    finally:
        _LOCAL.context = previous


def current() -> Any | None:
    """The request context bound to this thread, if any."""
    return getattr(_LOCAL, "context", None)


def can_elicit(context: Any | None) -> bool:
    """Whether the client declared the elicitation capability for this connection."""
    capabilities = getattr(context, "client_capabilities", None) if context is not None else None
    return capabilities is not None and getattr(capabilities, "elicitation", None) is not None


def safe_attribute(owner: Any, name: str) -> Any:
    """``owner.name``, or None when ``owner`` is None or the property refuses (the SDK's context raises outside a
    request)."""
    if owner is None:
        return None
    try:
        return getattr(owner, name, None)
    except (ValueError, RuntimeError, LookupError):
        return None


def client_summary(context: Any | None) -> dict[str, Any] | None:
    """Who the MCP client is and what it offers, from the initialize handshake: name, version, protocol version,
    the capabilities it declared and whether it can answer a question (elicitation). None outside a tool call."""
    session = safe_attribute(context, "session")
    if session is None:
        return None
    params = safe_attribute(session, "client_params")
    info = safe_attribute(params, "client_info")
    capabilities = safe_attribute(params, "capabilities") or safe_attribute(context, "client_capabilities")
    declared = []
    if capabilities is not None:
        dumped = capabilities.model_dump(exclude_none=True) if hasattr(capabilities, "model_dump") else {}
        declared = sorted(str(key) for key in dumped)
    return {
        "name": getattr(info, "name", None),
        "version": getattr(info, "version", None),
        "protocol": safe_attribute(session, "protocol_version"),
        "capabilities": declared,
        "elicitation": can_elicit(context),
    }


def confirm(message: str, *, policy: str = POLICY_AUTO, run: Runner = anyio.from_thread.run) -> str:
    """Asks the user ``message`` and returns ``accepted``, ``declined``, ``cancelled``, ``not offered`` or
    ``unsupported``.

    ``unsupported`` means nothing was asked (policy ``never``, or no tool call in progress) and ``not offered``
    that the client declared no elicitation; either way the caller does what the tool's own arguments say, and
    ``not offered`` is reported so the user knows the client's own approval was the only check. A client that
    fails to answer counts as ``cancelled``: when the question was asked, nothing is written without an answer."""
    context = current()
    if policy == POLICY_NEVER or context is None:
        return UNSUPPORTED
    if not can_elicit(context):
        return NOT_OFFERED
    try:
        result = run(context.elicit, message, CONFIRM_SCHEMA)
    except Exception as exc:  # any failure of the client to answer is a non-answer
        LOG.warning("confirmation not answered: %s", exc)
        return CANCELLED
    action = getattr(result, "action", None)
    if action == "accept":
        data = getattr(result, "data", None)
        return ACCEPTED if getattr(data, "apply", True) else DECLINED
    return DECLINED if action == "decline" else CANCELLED


def progress(done: float, total: float | None, message: str, *, run: Runner = anyio.from_thread.run) -> None:
    """Tells the client how far the tool got; nothing when no request context is bound or the client sent no
    progress token (the SDK drops the note then). Never fails the tool."""
    context = current()
    if context is None:
        return
    try:
        run(context.report_progress, done, total, message)
    except Exception as exc:  # progress is advisory
        LOG.debug("progress not sent: %s", exc)


def write_question(action: str, files: list[tuple[str, int]], limit: int = 10) -> str:
    """The confirmation text for edits: the action, then each file with the lines it changes."""
    lines = [f"{action}?"]
    lines.extend(f"- {path}: {count} line(s)" for path, count in files[:limit])
    if len(files) > limit:
        lines.append(f"- and {len(files) - limit} more file(s)")
    return "\n".join(lines)
