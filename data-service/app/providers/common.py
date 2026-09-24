"""Shared plumbing for broker providers: range chunking, request pacing, and a hub that carries
ticks from a websocket SDK's own thread to asyncio subscribers."""
from __future__ import annotations

import asyncio
import threading
import time
from collections.abc import AsyncIterator, Iterable

from app.models import Tick

DAY_MS = 24 * 60 * 60 * 1000


def chunk_ranges(frm: int, to: int, max_days: int) -> list[tuple[int, int]]:
    """Split [frm, to] (epoch ms) into consecutive windows of at most ``max_days``."""
    if frm > to:
        return []
    span = max_days * DAY_MS
    out = []
    start = frm
    while start <= to:
        end = min(to, start + span - 1)
        out.append((start, end))
        start = end + 1
    return out


class RateLimiter:
    """Spaces calls to at most ``rate`` per second. Thread-safe, so SDK calls made from worker
    threads (asyncio.to_thread) share one budget."""

    def __init__(self, rate: float) -> None:
        self._interval = 1.0 / rate if rate > 0 else 0.0
        self._next = 0.0
        self._lock = threading.Lock()

    def wait(self) -> None:
        with self._lock:
            now = time.monotonic()
            delay = self._next - now
            self._next = max(now, self._next) + self._interval
        if delay > 0:
            time.sleep(delay)


class TickHub:
    """Fan-out of ticks published from any thread to asyncio subscribers, each of which receives
    only the symbols it asked for. Tracks the union of wanted symbols so a provider can keep its
    websocket subscription in step (``on_change`` is called with the new union)."""

    def __init__(self, on_change=None, max_queue: int = 10_000) -> None:
        self._loop: asyncio.AbstractEventLoop | None = None
        self._subs: dict[int, tuple[asyncio.Queue, frozenset[str]]] = {}
        self._next_id = 0
        self._on_change = on_change
        self._max_queue = max_queue

    def wanted(self) -> set[str]:
        out: set[str] = set()
        for _, syms in self._subs.values():
            out |= syms
        return out

    def publish(self, tick: Tick) -> None:
        """Called from the SDK thread."""
        loop = self._loop
        if loop is not None and not loop.is_closed():
            loop.call_soon_threadsafe(self._dispatch, tick)

    def _dispatch(self, tick: Tick) -> None:
        for queue, syms in list(self._subs.values()):
            if tick.symbol in syms and queue.qsize() < self._max_queue:
                queue.put_nowait(tick)

    async def subscribe(self, symbols: Iterable[str]) -> AsyncIterator[Tick]:
        self._loop = asyncio.get_running_loop()
        sub_id = self._next_id
        self._next_id += 1
        queue: asyncio.Queue = asyncio.Queue()
        self._subs[sub_id] = (queue, frozenset(s.upper() for s in symbols))
        self._changed()
        try:
            while True:
                yield await queue.get()
        finally:
            self._subs.pop(sub_id, None)
            self._changed()

    def _changed(self) -> None:
        if self._on_change is not None:
            self._on_change(self.wanted())


class BrokerFeed:
    """Supervises one broker websocket so it is connected only while someone wants ticks and —
    with ``LIVE_MARKET_HOURS_ONLY`` — while the NSE session is open. Subclasses implement
    ``_open`` / ``_close`` / ``_resubscribe`` (blocking; run in worker threads) and call
    ``self.publish(tick)`` from the SDK's callback thread. SDK callbacks that see the socket drop
    should call ``self.mark_disconnected(reason)``; the supervisor then reconnects.
    """

    CHECK_SECONDS = 2.0
    RETRY_SECONDS = 10.0

    def __init__(self, name: str, market_hours_only: bool) -> None:
        self.name = name
        self._market_hours_only = market_hours_only
        self.hub = TickHub()
        self._task: asyncio.Task | None = None
        self._connected = False
        self._subscribed: set[str] = set()
        self.last_error: str | None = None
        self.last_tick_ms: int | None = None

    # ---- called by SDK threads ----
    def publish(self, tick: Tick) -> None:
        self.last_tick_ms = tick.timestamp
        self.hub.publish(tick)

    def mark_disconnected(self, reason: str | None = None) -> None:
        self._connected = False
        if reason:
            self.last_error = reason

    # ---- asyncio side ----
    async def stream(self, symbols: list[str]) -> AsyncIterator[Tick]:
        if self._task is None or self._task.done():
            self._task = asyncio.create_task(self._supervise())
        async for tick in self.hub.subscribe(symbols):
            yield tick

    def should_connect(self) -> bool:
        from app.market import is_market_open

        return bool(self.hub.wanted()) and (not self._market_hours_only or is_market_open())

    async def _supervise(self) -> None:
        while True:
            try:
                await self._step()
                await asyncio.sleep(self.CHECK_SECONDS)
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001 - keep supervising after SDK failures
                self.last_error = f"{type(exc).__name__}: {exc}"
                self._connected = False
                await asyncio.sleep(self.RETRY_SECONDS)

    async def _step(self) -> None:
        wanted = self.hub.wanted()
        if self.should_connect():
            if not self._connected:
                await asyncio.to_thread(self._open)
                self._connected = True
                self._subscribed = set()
                self.last_error = None
            if wanted != self._subscribed:
                await asyncio.to_thread(self._resubscribe, set(wanted), set(self._subscribed))
                self._subscribed = set(wanted)
        elif self._connected:
            await asyncio.to_thread(self._close)
            self._connected = False
            self._subscribed = set()

    def status(self) -> dict:
        from app.market import is_market_open

        return {
            "feed": self.name,
            "connected": self._connected,
            "subscribed": len(self._subscribed),
            "market_open": is_market_open(),
            "market_hours_only": self._market_hours_only,
            "last_tick_ms": self.last_tick_ms,
            "last_error": self.last_error,
        }

    async def aclose(self) -> None:
        if self._task is not None:
            self._task.cancel()
        if self._connected:
            try:
                await asyncio.to_thread(self._close)
            finally:
                self._connected = False

    # ---- subclass hooks (blocking) ----
    def _open(self) -> None:
        raise NotImplementedError

    def _close(self) -> None:
        raise NotImplementedError

    def _resubscribe(self, wanted: set[str], current: set[str]) -> None:
        raise NotImplementedError


def dedupe_sorted(candles: list, frm: int, to: int) -> list:
    """Keep candles inside [frm, to], one per timestamp (last wins), sorted by time."""
    by_ts = {c.timestamp: c for c in candles if frm <= c.timestamp <= to}
    return [by_ts[k] for k in sorted(by_ts)]


def normalize_symbol(tradingsymbol: str) -> str:
    """Broker index names contain spaces ("NIFTY 50"); app symbols don't ("NIFTY50")."""
    return tradingsymbol.replace(" ", "").upper()
