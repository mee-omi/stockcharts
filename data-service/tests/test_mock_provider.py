import asyncio
import subprocess
import sys
import time

from app.providers.mock import MockProvider

DAY = 24 * 60 * 60_000


def test_universe_has_equities_and_indices():
    items = asyncio.run(MockProvider().list_instruments())
    types = {i.symbol: i.instrument_type for i in items}
    assert types["RELIANCE"] == "EQ"
    assert types["NIFTY50"] == "INDEX"
    assert types["SENSEX"] == "INDEX"
    assert next(i for i in items if i.symbol == "SENSEX").exchange == "BSE"
    assert len(types) == len(items), "symbols must be unique"


def test_history_is_aligned_ordered_and_sane():
    frm, to = 1_700_000_000_000, 1_700_000_000_000 + 3 * 60 * 60_000
    candles = asyncio.run(MockProvider().get_historical("TCS", "5minute", frm, to))
    step = 5 * 60_000
    assert candles, "expected candles"
    assert all(c.timestamp % step == 0 for c in candles)
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


def test_timeframes_agree_on_the_same_price_path():
    provider = MockProvider()
    day = 1_780_000_000_000 - (1_780_000_000_000 % DAY)

    async def load():
        daily = await provider.get_historical("HDFCBANK", "day", day, day)
        minutes = await provider.get_historical("HDFCBANK", "minute", day, day + DAY - 60_000)
        return daily[0], minutes

    d, minutes = asyncio.run(load())
    assert len(minutes) == 1440
    assert d.open == minutes[0].open
    assert d.close == minutes[-1].close
    # Daily range is sampled from the 1m ranges, so it nests inside them.
    assert min(m.low for m in minutes) <= d.low and d.high <= max(m.high for m in minutes)
    assert any(m.open != m.close for m in minutes), "1m candles must not be flat"


def test_stream_starts_from_latest_history():
    provider = MockProvider()

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
