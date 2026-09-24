"""Server-sent events with keepalives that keep flowing while the source is quiet (e.g. outside
market hours), so proxies and the backend's reader never see an idle connection."""
from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator, Awaitable, Callable

from pydantic import BaseModel

_DONE = object()


async def sse_events(
    source: AsyncIterator[BaseModel],
    is_disconnected: Callable[[], Awaitable[bool]],
    ping_seconds: float = 15.0,
    on_event: Callable[[], None] | None = None,
) -> AsyncIterator[str]:
    queue: asyncio.Queue = asyncio.Queue(maxsize=10_000)

    async def pump() -> None:
        try:
            async for item in source:
                await queue.put(item)
        finally:
            await queue.put(_DONE)

    task = asyncio.create_task(pump())
    try:
        yield ": connected\n\n"
        while True:
            try:
                item = await asyncio.wait_for(queue.get(), timeout=ping_seconds)
            except asyncio.TimeoutError:
                if await is_disconnected():
                    return
                yield ": ping\n\n"
                continue
            if item is _DONE:
                if task.done() and task.exception() is not None:
                    raise task.exception()
                return
            if on_event is not None:
                on_event()
            yield f"data: {json.dumps(item.model_dump())}\n\n"
    finally:
        task.cancel()
        try:
            await task
        except (asyncio.CancelledError, Exception):  # noqa: BLE001 - shutting down
            pass
