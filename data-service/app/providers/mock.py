"""Synthetic market-data provider — the default so the stack runs without credentials.

Each symbol's price is a deterministic function of time: a per-symbol drift plus a sum of
sine cycles (45 minutes up to a year, with random phases) plus hashed per-minute noise. Every
timeframe samples that same curve, so a daily candle agrees with the 1-minute candles inside it,
quotes and cross-timeframe views are consistent, and live ticks continue the charted history.
Output is stable across reloads and process restarts (seeds use crc32, not the salted str hash).
"""
from __future__ import annotations

import asyncio
import math
import random
import time
import zlib
from typing import AsyncIterator

from app.config import settings
from app.models import Candle, Instrument, Tick
from app.providers.base import MarketDataProvider
from app.providers.nse_universe import INDICES, NSE_STOCKS

_MINUTE_MS = 60_000
_INTERVAL_MS = {
    "minute": _MINUTE_MS,
    "5minute": 5 * _MINUTE_MS,
    "15minute": 15 * _MINUTE_MS,
    "30minute": 30 * _MINUTE_MS,
    "60minute": 60 * _MINUTE_MS,
    "day": 24 * 60 * _MINUTE_MS,
}
_YEAR_MS = 365 * 24 * 60 * _MINUTE_MS
# Cycle periods in minutes (45 min … 1 year); amplitude grows sub-linearly with the period.
_PERIODS_MIN = (45, 180, 720, 2_880, 10_080, 43_200, 131_400, 525_600)
_MINUTE_NOISE = 0.0015
# Max samples per candle used to find its high/low (bounds cost on long daily ranges).
_MAX_SAMPLES = 48


def _unit(key: str) -> float:
    """Deterministic pseudo-random number in [-0.5, 0.5) for a string key."""
    return zlib.crc32(key.encode()) / 2**32 - 0.5


class _Curve:
    """The deterministic price path of one symbol."""

    def __init__(self, symbol: str, base: float) -> None:
        rng = random.Random(zlib.crc32(symbol.encode()))
        self.symbol = symbol
        self.base = base
        self.drift = rng.uniform(-0.10, 0.18)  # log-return per year
        self.cycles = [
            (0.004 * (p / 45) ** 0.35, 2 * math.pi / (p * _MINUTE_MS), rng.uniform(0, 2 * math.pi))
            for p in _PERIODS_MIN
        ]
        # Anchor so that "now-ish" prices sit near the seed price rather than drifting off.
        self.anchor = 1_767_225_600_000  # 2026-01-01T00:00Z

    def price(self, t_ms: int) -> float:
        x = self.drift * (t_ms - self.anchor) / _YEAR_MS
        for amp, omega, phase in self.cycles:
            x += amp * math.sin(omega * t_ms + phase)
        x += _MINUTE_NOISE * _unit(f"{self.symbol}:{t_ms // _MINUTE_MS}")
        return self.base * math.exp(x)

    def minute_range(self, minute_start_ms: int) -> tuple[float, float]:
        """(high, low) of the one-minute bar starting at ``minute_start_ms``."""
        o = self.price(minute_start_ms)
        c = self.price(minute_start_ms + _MINUTE_MS)
        wick = abs(_unit(f"{self.symbol}:w:{minute_start_ms // _MINUTE_MS}")) * 0.0012
        return max(o, c) * (1 + wick), min(o, c) * (1 - wick)


class MockProvider(MarketDataProvider):
    name = "mock"

    def __init__(self) -> None:
        self._instruments = [
            Instrument(symbol=s, name=n, instrument_token=t, exchange="NSE")
            for (s, n, t, _seed) in NSE_STOCKS
        ] + [
            Instrument(symbol=s, name=n, instrument_token=t, exchange=ex, instrument_type="INDEX")
            for (s, n, t, _seed, ex) in INDICES
        ]
        self._seed_price = {s: p for (s, _n, _t, p) in NSE_STOCKS}
        self._seed_price.update({s: p for (s, _n, _t, p, _ex) in INDICES})
        # Indices have no traded volume.
        self._indices = {s for (s, *_rest) in INDICES}
        self._curves: dict[str, _Curve] = {}

    def _curve(self, symbol: str) -> _Curve:
        sym = symbol.upper()
        curve = self._curves.get(sym)
        if curve is None:
            curve = _Curve(sym, self._seed_price.get(sym, 1000.0))
            self._curves[sym] = curve
        return curve

    async def list_instruments(self) -> list[Instrument]:
        return self._instruments

    async def get_historical(
        self, symbol: str, interval: str, frm: int, to: int
    ) -> list[Candle]:
        step = _INTERVAL_MS.get(interval, _MINUTE_MS)
        curve = self._curve(symbol)
        is_index = symbol.upper() in self._indices
        minutes = step // _MINUTE_MS
        stride = max(1, math.ceil(minutes / _MAX_SAMPLES)) * _MINUTE_MS

        candles: list[Candle] = []
        ts = frm - (frm % step)  # align to the interval grid
        while ts <= to:
            open_ = curve.price(ts)
            close = curve.price(ts + step)  # = next bar's open, so the series is continuous
            # High/low come from per-minute ranges sampled across the bar (always including its
            # first and last minute), so a bar's range nests inside the finer bars it spans.
            last_minute = ts + step - _MINUTE_MS
            points = list(range(ts, last_minute, stride)) + [last_minute]
            ranges = [curve.minute_range(t) for t in points]
            high = max(h for h, _ in ranges)
            low = min(lo for _, lo in ranges)
            if is_index:
                volume = 0.0
            else:
                volume = float(int(4_000 * minutes * (1 + _unit(f"{symbol.upper()}:v:{interval}:{ts}"))))
            candles.append(
                Candle(
                    timestamp=ts,
                    open=round(open_, 2),
                    high=round(high, 2),
                    low=round(low, 2),
                    close=round(close, 2),
                    volume=volume,
                )
            )
            ts += step
        return candles

    async def stream_ticks(self, symbols: list[str]) -> AsyncIterator[Tick]:
        syms = [s.upper() for s in symbols]
        rng = random.Random()
        while True:
            now = int(time.time() * 1000)
            for sym in syms:
                # Follow the same curve as history, with a little intra-minute jitter.
                px = self._curve(sym).price(now) * (1 + rng.uniform(-0.0004, 0.0004))
                yield Tick(
                    symbol=sym,
                    ltp=round(px, 2),
                    timestamp=now,
                    volume=0.0 if sym in self._indices else float(rng.randint(1, 5000)),
                )
            await asyncio.sleep(settings.tick_interval_seconds)
