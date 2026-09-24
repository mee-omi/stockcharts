"""Real Zerodha provider backed by PyZData (enctoken auth, no paid API).

Activated with ``MARKET_DATA_PROVIDER=zerodha`` and a valid ``ENCTOKEN``.

PyZData (https://github.com/vikassharma545/Historical-Market-data-From-Zerodha) is a
synchronous, pandas-based library, so every call is run in a worker thread to keep the
FastAPI event loop responsive. It serves historical/intraday candles only; the live
"stream" here is implemented by polling the most recent 1-minute candle, which is the
best a no-subscription enctoken can do. For a true tick feed, use the KiteTicker provider.
"""
from __future__ import annotations

import asyncio
import time
from datetime import datetime, timezone
from typing import AsyncIterator

from app.config import settings
from app.models import Candle, Instrument, Tick
from app.providers.base import MarketDataProvider

# Our wire interval -> PyZData interval name.
_INTERVAL_MAP = {
    "minute": "minute",
    "5minute": "5minute",
    "15minute": "15minute",
    "30minute": "30minute",
    "60minute": "60minute",
    "day": "day",
}


def _to_dt(epoch_ms: int) -> datetime:
    return datetime.fromtimestamp(epoch_ms / 1000, tz=timezone.utc)


class ZerodhaEnctokenProvider(MarketDataProvider):
    name = "zerodha"

    def __init__(self, enctoken: str | None) -> None:
        if not enctoken:
            raise ValueError(
                "ENCTOKEN is required when MARKET_DATA_PROVIDER=zerodha. "
                "Get it from kite.zerodha.com cookies."
            )
        # Imported lazily so the dependency is only needed for this provider.
        from pyzdata import PyZData  # type: ignore

        self._client = PyZData(enctoken=enctoken)
        self._token_cache: dict[str, int] = {}

    # ----- instruments -----------------------------------------------------
    async def list_instruments(self) -> list[Instrument]:
        return await asyncio.to_thread(self._list_instruments_sync)

    def _list_instruments_sync(self) -> list[Instrument]:
        # PyZData exposes the NSE instrument master; shape may be a DataFrame.
        df = self._client.search_instruments("", exchange="NSE")
        out: list[Instrument] = []
        for _, row in df.iterrows():
            sym = str(row.get("tradingsymbol", "")).strip()
            if not sym:
                continue
            token = int(row.get("instrument_token", 0))
            self._token_cache[sym] = token
            # Kite's instrument dump marks indices with segment "INDICES".
            is_index = str(row.get("segment", "")).upper() == "INDICES"
            out.append(
                Instrument(
                    symbol=sym,
                    name=str(row.get("name", sym)),
                    instrument_token=token,
                    exchange="NSE",
                    instrument_type="INDEX" if is_index else "EQ",
                )
            )
        return out

    def _resolve_token(self, symbol: str) -> int:
        if symbol in self._token_cache:
            return self._token_cache[symbol]
        token = int(self._client.get_instrument_token(symbol, "NSE"))
        self._token_cache[symbol] = token
        return token

    # ----- historical ------------------------------------------------------
    async def get_historical(
        self, symbol: str, interval: str, frm: int, to: int
    ) -> list[Candle]:
        return await asyncio.to_thread(
            self._get_historical_sync, symbol, interval, frm, to
        )

    def _get_historical_sync(
        self, symbol: str, interval: str, frm: int, to: int
    ) -> list[Candle]:
        token = self._resolve_token(symbol.upper())
        pyz_interval = _INTERVAL_MAP.get(interval, "minute")
        df = self._client.get_data(
            token,
            _to_dt(frm).strftime("%Y-%m-%d"),
            _to_dt(to).strftime("%Y-%m-%d"),
            pyz_interval,
        )
        candles: list[Candle] = []
        for _, row in df.iterrows():
            dt = row["datetime"]
            ts = int(dt.timestamp() * 1000) if hasattr(dt, "timestamp") else int(dt)
            candles.append(
                Candle(
                    timestamp=ts,
                    open=float(row["open"]),
                    high=float(row["high"]),
                    low=float(row["low"]),
                    close=float(row["close"]),
                    volume=float(row["volume"]),
                )
            )
        return candles

    # ----- live (poll latest 1m candle) ------------------------------------
    async def stream_ticks(self, symbols: list[str]) -> AsyncIterator[Tick]:
        while True:
            now = int(time.time() * 1000)
            start = now - 5 * 60_000  # last 5 minutes window
            for sym in symbols:
                try:
                    candles = await self.get_historical(sym, "minute", start, now)
                except Exception:  # noqa: BLE001 - keep the stream alive on transient errors
                    continue
                if candles:
                    last = candles[-1]
                    yield Tick(
                        symbol=sym.upper(),
                        ltp=last.close,
                        timestamp=last.timestamp,
                        volume=last.volume,
                    )
            await asyncio.sleep(settings.zerodha_poll_seconds)
