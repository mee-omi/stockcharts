import asyncio
import subprocess
import sys
import time
from datetime import datetime

from app.market import IST, is_market_open, to_ms
from app.providers.mock import MockProvider

DAY = 24 * 60 * 60_000


def ist(text: str) -> int:
    return to_ms(datetime.fromisoformat(text).replace(tzinfo=IST))


def test_universe_has_equities_and_indices():
    items = asyncio.run(MockProvider().list_instruments())
    types = {i.symbol: i.instrument_type for i in items}
    assert types["RELIANCE"] == "EQ"
    assert types["NIFTY50"] == "INDEX"
    assert types["SENSEX"] == "INDEX"
    assert next(i for i in items if i.symbol == "SENSEX").exchange == "BSE"
    assert len(types) == len(items), "symbols must be unique"


def test_history_is_aligned_ordered_and_sane():
    frm, to = ist("2026-05-21T10:00"), ist("2026-05-21T13:00")
    candles = asyncio.run(MockProvider().get_historical("TCS", "5minute", frm, to))
    step = 5 * 60_000
    assert candles, "expected candles"
    assert all(c.timestamp % step == 0 for c in candles)
    assert candles[0].timestamp == frm and candles[-1].timestamp == to
    assert [c.timestamp for c in candles] == sorted(c.timestamp for c in candles)
    for c in candles:
        assert c.low <= min(c.open, c.close) <= max(c.open, c.close) <= c.high
        assert c.volume > 0


def test_indices_have_no_volume():
    now = int(time.time() * 1000)
    candles = asyncio.run(MockProvider().get_historical("NIFTY50", "day", now - 30 * DAY, now))
    assert candles and all(c.volume == 0 for c in candles)


def _series_in_subprocess() -> str:
    code = (
        "import asyncio;from app.providers.mock import MockProvider;"
        "c=asyncio.run(MockProvider().get_historical('INFY','day',1700000000000,1702000000000));"
        "print(','.join(str(x.close) for x in c))"
    )
    return subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, check=True).stdout


def test_history_is_deterministic_across_processes():
    # Regression: seeding used the built-in str hash, which is randomised per process.
    assert _series_in_subprocess() == _series_in_subprocess()


def test_bars_follow_the_nse_session():
    provider = MockProvider(always_open=False)
    # Thu 21 May 2026 → Mon 25 May 2026: a weekend in between.
    frm, to = ist("2026-05-21T00:00"), ist("2026-05-25T23:59")

    hourly = asyncio.run(provider.get_historical("INFY", "60minute", frm, to))
    starts = [datetime.fromtimestamp(c.timestamp / 1000, IST).strftime("%a %H:%M") for c in hourly]
    assert starts[:7] == ["Thu 09:15", "Thu 10:15", "Thu 11:15", "Thu 12:15", "Thu 13:15", "Thu 14:15", "Thu 15:15"]
    assert len(hourly) == 3 * 7 and not any(s.startswith(("Sat", "Sun")) for s in starts)

    daily = asyncio.run(provider.get_historical("INFY", "day", frm, to))
    assert [c.timestamp for c in daily] == [ist("2026-05-21T00:00"), ist("2026-05-22T00:00"), ist("2026-05-25T00:00")]
    # Prices move between sessions, so the next day opens with a gap.
    assert daily[1].open != daily[0].close


def test_holidays_have_no_bars(monkeypatch):
    from app.config import settings

    monkeypatch.setattr(settings, "nse_holidays", "2026-05-01")
    minutes = asyncio.run(MockProvider(always_open=False).get_historical(
        "INFY", "minute", ist("2026-05-01T00:00"), ist("2026-05-01T23:59")))
    assert minutes == []
    monkeypatch.setattr(settings, "nse_holidays", "")
    assert asyncio.run(MockProvider(always_open=False).get_historical(
        "INFY", "minute", ist("2026-05-01T00:00"), ist("2026-05-01T23:59")))


def test_always_open_uses_the_utc_grid():
    candles = asyncio.run(MockProvider(always_open=True).get_historical(
        "TCS", "60minute", ist("2026-05-23T00:00"), ist("2026-05-23T05:00")))  # a Saturday
    assert len(candles) == 6 and all(c.timestamp % (60 * 60_000) == 0 for c in candles)


def test_timeframes_agree_on_the_same_price_path():
    provider = MockProvider(always_open=False)
    day = ist("2026-05-28T00:00")  # a Thursday

    async def load():
        daily = await provider.get_historical("HDFCBANK", "day", day, day)
        minutes = await provider.get_historical("HDFCBANK", "minute", day, day + DAY - 60_000)
        return daily[0], minutes

    d, minutes = asyncio.run(load())
    assert len(minutes) == 375  # 09:15–15:30
    assert d.open == minutes[0].open
    assert d.close == minutes[-1].close
    # Daily range is sampled from the 1m ranges, so it nests inside them.
    assert min(m.low for m in minutes) <= d.low and d.high <= max(m.high for m in minutes)
    assert any(m.open != m.close for m in minutes), "1m candles must not be flat"


def test_stream_starts_from_latest_history():
    provider = MockProvider(always_open=True)

    async def first_tick():
        now = int(time.time() * 1000)
        hist = await provider.get_historical("SBIN", "minute", now - 2 * 60 * 60_000, now)
        gen = provider.stream_ticks(["sbin"])
        tick = await gen.__anext__()
        await gen.aclose()
        return hist[-1].close, tick

    last_close, tick = asyncio.run(first_tick())
    assert tick.symbol == "SBIN"
    # First tick is a small random step away from the last close, not a jump to the seed price.
    assert abs(tick.ltp - last_close) / last_close < 0.01


def test_stream_is_silent_while_the_market_is_closed(monkeypatch):
    import app.providers.mock as mock

    monkeypatch.setattr(mock, "is_market_open", lambda: False)
    sleeps: list[float] = []

    async def fake_sleep(seconds):
        sleeps.append(seconds)
        if len(sleeps) >= 2:
            raise asyncio.CancelledError

    monkeypatch.setattr(mock.asyncio, "sleep", fake_sleep)

    async def run():
        gen = MockProvider(always_open=False).stream_ticks(["SBIN"])
        try:
            await gen.__anext__()
        except asyncio.CancelledError:
            return "no ticks"
        return "tick"

    assert asyncio.run(run()) == "no ticks"
    assert sleeps == [30, 30]
    assert isinstance(is_market_open(), bool)
