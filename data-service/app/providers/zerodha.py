"""Zerodha provider backed by PyZData (enctoken auth, no paid API).

Activated with ``MARKET_DATA_PROVIDER=zerodha`` and a valid ``ENCTOKEN`` (it expires daily — copy a
fresh one from kite.zerodha.com after logging in).

* History: PyZData downloads Kite's historical API in date windows sized to Kite's per-request
  limits and paces the requests itself. It returns naive IST timestamps, converted here to UTC ms.
* Live: an enctoken has no websocket access, so the "stream" polls each symbol's latest 1-minute
  candle during market hours and sends the whole bar (open/high/low/close/volume) so the consumer
  can replace its bar rather than guess from closes. For true ticks use ``kiteticker``
  (official Kite Connect) instead.
"""
from __future__ import annotations

import asyncio
import time
from collections.abc import AsyncIterator
from datetime import timedelta

from app.config import settings
from app.market import IST, is_market_open, ms_to_ist, to_ms
from app.models import Candle, Instrument, Tick
from app.providers.base import MarketDataProvider
from app.providers.common import dedupe_sorted, normalize_symbol

# Our wire interval -> PyZData Interval value.
_INTERVAL_MAP = {
    "minute": "minute",
    "5minute": "5minute",
    "15minute": "15minute",
    "30minute": "30minute",
    "60minute": "60minute",
    "day": "day",
}


def _instrument_rows(df) -> list[Instrument]:
    """NSE equities and indices from a Kite instruments master (DataFrame or list of dicts)."""
    rows = df.to_dict("records") if hasattr(df, "to_dict") else list(df)
    out: list[Instrument] = []
    for row in rows:
        if str(row.get("exchange", "NSE")).upper() != "NSE":
            continue
        segment = str(row.get("segment", "")).upper()
        itype = str(row.get("instrument_type", "")).upper()
        is_index = segment == "INDICES"
        if not is_index and itype not in ("EQ", ""):
            continue
        tradingsymbol = str(row.get("tradingsymbol", "")).strip()
        if not tradingsymbol:
            continue
        out.append(Instrument(
            symbol=normalize_symbol(tradingsymbol),
            name=str(row.get("name") or tradingsymbol),
            instrument_token=int(row.get("instrument_token", 0)),
            exchange="NSE",
            instrument_type="INDEX" if is_index else "EQ",
        ))
    return out


class ZerodhaEnctokenProvider(MarketDataProvider):
    name = "zerodha"

    def __init__(self, enctoken: str | None, client=None) -> None:
        if client is None:
            if not enctoken:
                raise ValueError(
                    "ENCTOKEN is required when MARKET_DATA_PROVIDER=zerodha. "
                    "Get it from kite.zerodha.com cookies."
                )
            # Imported lazily so the dependency is only needed for this provider.
            from pyzdata import PyZData  # type: ignore

            client = PyZData(enctoken=enctoken)
        self._client = client
        self._tokens: dict[str, int] = {}
        self._last_error: str | None = None

    async def list_instruments(self) -> list[Instrument]:
        items = await asyncio.to_thread(lambda: _instrument_rows(self._client.instruments()))
        self._tokens = {i.symbol: i.instrument_token for i in items}
        return items

    async def _token(self, symbol: str) -> int:
        sym = symbol.upper()
        if sym not in self._tokens:
            await self.list_instruments()
        if sym not in self._tokens:
            raise ValueError(f"Unknown Zerodha symbol: {symbol}")
        return self._tokens[sym]

    async def get_historical(self, symbol: str, interval: str, frm: int, to: int) -> list[Candle]:
        token = await self._token(symbol)
        return await asyncio.to_thread(self._get_historical_sync, token, interval, frm, to)

    def _get_historical_sync(self, token: int, interval: str, frm: int, to: int) -> list[Candle]:
        from pyzdata import Interval  # type: ignore

        start, end = ms_to_ist(frm).date(), ms_to_ist(to).date()
        df = self._client.get_data(
            token, start.isoformat(), end.isoformat(), Interval(_INTERVAL_MAP.get(interval, "minute"))
        )
        candles = [
            Candle(
                timestamp=to_ms(row["datetime"].to_pydatetime() if hasattr(row["datetime"], "to_pydatetime")
                                else row["datetime"]),  # naive IST → UTC ms
                open=float(row["open"]),
                high=float(row["high"]),
                low=float(row["low"]),
                close=float(row["close"]),
                volume=float(row["volume"]),
            )
            for row in df.to_dict("records")
        ]
        return dedupe_sorted(candles, frm, to)

    async def stream_ticks(self, symbols: list[str]) -> AsyncIterator[Tick]:
        while True:
            if settings.live_market_hours_only and not is_market_open():
                await asyncio.sleep(30)
                continue
            now = int(time.time() * 1000)
            start = now - int(timedelta(minutes=5).total_seconds() * 1000)
            for sym in symbols:
                try:
                    candles = await self.get_historical(sym, "minute", start, now)
                    self._last_error = None
                except Exception as exc:  # noqa: BLE001 - keep the stream alive on transient errors
                    self._last_error = f"{sym}: {exc}"
                    continue
                if candles:
                    last = candles[-1]
                    yield Tick(symbol=sym.upper(), ltp=last.close, timestamp=last.timestamp, bar=last)
            await asyncio.sleep(settings.zerodha_poll_seconds)

    def status(self) -> dict:
        return {
            "feed": "polling (enctoken)",
            "market_open": is_market_open(),
            "market_hours_only": settings.live_market_hours_only,
            "last_error": self._last_error,
            "timezone": str(IST),
        }
