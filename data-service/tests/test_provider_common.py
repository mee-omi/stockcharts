import asyncio
import threading
import time

from app.models import Candle, Tick
from app.providers.common import (
    DAY_MS,
    BrokerFeed,
    RateLimiter,
    TickHub,
    chunk_ranges,
    dedupe_sorted,
    normalize_symbol,
)
from app.sse import sse_events


def test_chunk_ranges_cover_the_range_without_overlap():
    chunks = chunk_ranges(0, 95 * DAY_MS, 30)
    assert chunks[0][0] == 0 and chunks[-1][1] == 95 * DAY_MS
    assert len(chunks) == 4
    for (a0, a1), (b0, _) in zip(chunks, chunks[1:]):
        assert b0 == a1 + 1
        assert a1 - a0 < 30 * DAY_MS
    assert chunk_ranges(10, 5, 30) == []


def test_dedupe_sorted_and_symbol_normalisation():
    c = lambda t, v: Candle(timestamp=t, open=v, high=v, low=v, close=v, volume=1)  # noqa: E731
    out = dedupe_sorted([c(3, 1), c(1, 1), c(3, 2), c(9, 1)], 0, 5)
    assert [x.timestamp for x in out] == [1, 3] and out[1].close == 2
    assert normalize_symbol("NIFTY 50") == "NIFTY50"


def test_rate_limiter_spaces_calls():
    limiter = RateLimiter(20)  # 50 ms apart
    start = time.monotonic()
    for _ in range(4):
        limiter.wait()
    assert time.monotonic() - start >= 0.14


def test_tick_hub_delivers_thread_published_ticks_to_matching_subscribers():
    async def run():
        hub = TickHub()
        got = []

        async def consume():
            async for t in hub.subscribe(["INFY"]):
                got.append(t.symbol)
                if len(got) == 2:
                    return

        task = asyncio.create_task(consume())
        await asyncio.sleep(0.01)
        assert hub.wanted() == {"INFY"}

        def producer():
            for sym in ["TCS", "INFY", "SBIN", "INFY"]:
                hub.publish(Tick(symbol=sym, ltp=1, timestamp=1))

        threading.Thread(target=producer).start()
        await asyncio.wait_for(task, 2)
        await asyncio.sleep(0)
        return got, hub.wanted()

    got, wanted_after = asyncio.run(run())
    assert got == ["INFY", "INFY"]
    assert wanted_after == set()


def test_sse_sends_keepalives_while_the_source_is_quiet():
    async def slow():
        await asyncio.sleep(0.25)
        yield Tick(symbol="X", ltp=1, timestamp=1)

    async def not_disconnected():
        return False

    async def run():
        out = []
        async for chunk in sse_events(slow(), not_disconnected, ping_seconds=0.05):
            out.append(chunk)
        return out

    out = asyncio.run(run())
    assert out[0] == ": connected\n\n"
    assert out.count(": ping\n\n") >= 2
    assert out[-1].startswith("data: ") and '"symbol": "X"' in out[-1]


class _FakeFeed(BrokerFeed):
    CHECK_SECONDS = 0.01

    def __init__(self):
        super().__init__("fake", market_hours_only=True)
        self.calls = []

    def _open(self):
        self.calls.append("open")

    def _close(self):
        self.calls.append("close")

    def _resubscribe(self, wanted, current):
        self.calls.append(("sub", tuple(sorted(wanted - current)), tuple(sorted(current - wanted))))


def test_broker_feed_connects_only_during_the_session(monkeypatch):
    import app.market as market

    state = {"open": False}
    monkeypatch.setattr(market, "is_market_open", lambda at=None: state["open"])

    async def run():
        feed = _FakeFeed()

        async def consume():
            async for _ in feed.stream(["INFY", "TCS"]):
                pass

        task = asyncio.create_task(consume())
        await asyncio.sleep(0.05)
        closed_calls = list(feed.calls)
        state["open"] = True
        await asyncio.sleep(0.05)
        state["open"] = False
        await asyncio.sleep(0.05)
        task.cancel()
        await feed.aclose()
        return closed_calls, feed.calls

    closed_calls, calls = asyncio.run(run())
    assert closed_calls == []  # market closed: never connects
    assert calls[:2] == ["open", ("sub", ("INFY", "TCS"), ())]
    assert "close" in calls  # disconnects when the session ends
