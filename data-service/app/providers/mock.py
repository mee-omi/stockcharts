"""Synthetic market-data provider — the default so the stack runs without credentials.

Produces deterministic random-walk OHLCV history (seeded per symbol, so reloads are
stable) and a continuous live tick stream.
"""
from __future__ import annotations

import asyncio
import math
import random
import time
from typing import AsyncIterator

from app.config import settings
from app.models import Candle, Instrument, Tick
from app.providers.base import MarketDataProvider
from app.providers.nse_universe import NSE_STOCKS

_INTERVAL_MS = {
    "minute": 60_000,
    "5minute": 5 * 60_000,
    "15minute": 15 * 60_000,
    "30minute": 30 * 60_000,
    "60minute": 60 * 60_000,
    "day": 24 * 60 * 60_000,
}


class MockProvider(MarketDataProvider):
    name = "mock"

    def __init__(self) -> None:
        self._instruments = [
            Instrument(symbol=s, name=n, instrument_token=t, exchange="NSE")
            for (s, n, t, _seed) in NSE_STOCKS
        ]
        self._seed_price = {s: p for (s, _n, _t, p) in NSE_STOCKS}

    async def list_instruments(self) -> list[Instrument]:
        return self._instruments

    async def get_historical(
        self, symbol: str, interval: str, frm: int, to: int
    ) -> list[Candle]:
        step = _INTERVAL_MS.get(interval, 60_000)
        base = self._seed_price.get(symbol.upper(), 1000.0)
        # Deterministic per (symbol, interval) so charts are stable across reloads.
        rng = random.Random(hash((symbol.upper(), interval)) & 0xFFFFFFFF)

        candles: list[Candle] = []
        price = base
        # Align start to the interval grid.
        ts = frm - (frm % step)
        i = 0
        while ts <= to:
            # Gentle drift + noise + a slow sine cycle for visual realism.
            drift = math.sin(i / 40.0) * base * 0.0008
            shock = rng.uniform(-1, 1) * base * 0.004
            open_ = price
            close = max(0.5, open_ + drift + shock)
            high = max(open_, close) * (1 + abs(rng.uniform(0, 0.003)))
            low = min(open_, close) * (1 - abs(rng.uniform(0, 0.003)))
            volume = float(rng.randint(10_000, 500_000))
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
            price = close
            ts += step
            i += 1
        return candles

    async def stream_ticks(self, symbols: list[str]) -> AsyncIterator[Tick]:
        prices = {
            s.upper(): self._seed_price.get(s.upper(), 1000.0) for s in symbols
        }
        rng = random.Random()
        while True:
            now = int(time.time() * 1000)
            for sym, px in list(prices.items()):
                px = max(0.5, px + rng.uniform(-1, 1) * px * 0.0015)
                prices[sym] = px
                yield Tick(
                    symbol=sym,
                    ltp=round(px, 2),
                    timestamp=now,
                    volume=float(rng.randint(1, 5000)),
                )
            await asyncio.sleep(settings.tick_interval_seconds)
