"""Zerodha via the official Kite Connect API: historical candles + the KiteTicker websocket.

Activated with ``MARKET_DATA_PROVIDER=kiteticker`` and ``ZERODHA_API_KEY`` + ``ZERODHA_ACCESS_TOKEN``
(a Kite Connect app subscription; the access token comes from the daily login flow and expires
the next morning — refresh it via the data-source dialog or env and switch the provider again).

* History: ``historical_data`` in windows sized to Kite's per-request limits, paced below the
  historical API's rate limit, IST in and out.
* Live: KiteTicker in FULL mode, connected only while ticks are wanted and (by default) while the
  NSE session is open. Ticks carry the day's cumulative volume; the backend derives per-minute
  volume from it. KiteTicker reconnects and resubscribes on its own after network drops.
"""
from __future__ import annotations

import asyncio
import threading
from collections.abc import AsyncIterator
from datetime import datetime

from app.config import settings
from app.market import ms_to_ist, to_ms
from app.models import Candle, Instrument, Tick
from app.providers.base import MarketDataProvider
from app.providers.common import BrokerFeed, RateLimiter, chunk_ranges, dedupe_sorted
from app.providers.zerodha import _instrument_rows

# Wire interval -> (Kite interval name, max days per historical request).
_KITE_INTERVALS = {
    "minute": ("minute", 60),
    "5minute": ("5minute", 100),
    "15minute": ("15minute", 200),
    "30minute": ("30minute", 200),
    "60minute": ("60minute", 400),
    "day": ("day", 2000),
}


def kite_tick_to_tick(t: dict, symbol_by_token: dict[int, str]) -> Tick | None:
    """Normalise one KiteTicker tick dict (any mode) to our Tick."""
    symbol = symbol_by_token.get(int(t.get("instrument_token", 0)))
    if symbol is None or t.get("last_price") is None:
        return None
    ts = t.get("exchange_timestamp") or t.get("last_trade_time")
    ms = int(ts.timestamp() * 1000) if isinstance(ts, datetime) else int(datetime.now().timestamp() * 1000)
    volume = t.get("volume_traded") if t.get("tradable", True) else None
    return Tick(symbol=symbol, ltp=float(t["last_price"]), timestamp=ms,
                day_volume=float(volume) if volume is not None else None)


class _KiteFeed(BrokerFeed):
    def __init__(self, api_key: str, access_token: str, provider: "KiteConnectProvider") -> None:
        super().__init__("kiteticker", settings.live_market_hours_only)
        self._api_key = api_key
        self._access_token = access_token
        self._provider = provider
        self._ticker = None
        self._opened = threading.Event()

    def _open(self) -> None:
        from kiteconnect import KiteTicker  # type: ignore

        self._opened.clear()
        ticker = KiteTicker(self._api_key, self._access_token)
        ticker.on_ticks = self._on_ticks
        ticker.on_connect = lambda ws, response: self._opened.set()
        ticker.on_noreconnect = lambda ws: self.mark_disconnected("KiteTicker gave up reconnecting")
        ticker.on_error = lambda ws, code, reason: setattr(self, "last_error", f"{code}: {reason}")
        ticker.connect(threaded=True)
        if not self._opened.wait(15):
            ticker.close()
            raise ConnectionError("KiteTicker did not connect within 15s (check the access token)")
        self._ticker = ticker

    def _on_ticks(self, ws, ticks: list[dict]) -> None:
        for t in ticks:
            tick = kite_tick_to_tick(t, self._provider.symbol_by_token)
            if tick is not None:
                self.publish(tick)

    def _close(self) -> None:
        if self._ticker is not None:
            from twisted.internet import reactor  # type: ignore

            reactor.callFromThread(self._ticker.close)
            self._ticker = None

    def _resubscribe(self, wanted: set[str], current: set[str]) -> None:
        from twisted.internet import reactor  # type: ignore

        ticker = self._ticker
        tokens = self._provider.tokens_for
        add, remove = tokens(wanted - current), tokens(current - wanted)
        if remove:
            reactor.callFromThread(ticker.unsubscribe, remove)
        if add:
            reactor.callFromThread(ticker.subscribe, add)
            reactor.callFromThread(ticker.set_mode, ticker.MODE_FULL, add)


class KiteConnectProvider(MarketDataProvider):
    name = "kiteticker"

    def __init__(self, api_key: str | None, access_token: str | None, kite=None) -> None:
        if kite is None:
            if not api_key or not access_token:
                raise ValueError(
                    "ZERODHA_API_KEY and ZERODHA_ACCESS_TOKEN (Kite Connect) are required for the "
                    "kiteticker provider."
                )
            from kiteconnect import KiteConnect  # type: ignore

            kite = KiteConnect(api_key=api_key)
            kite.set_access_token(access_token)
        self._kite = kite
        self._limiter = RateLimiter(settings.kite_history_rps)
        self._tokens: dict[str, int] = {}
        self.symbol_by_token: dict[int, str] = {}
        self._feed = _KiteFeed(api_key or "", access_token or "", self) if api_key else None

    async def list_instruments(self) -> list[Instrument]:
        items = await asyncio.to_thread(lambda: _instrument_rows(self._kite.instruments("NSE")))
        self._tokens = {i.symbol: i.instrument_token for i in items}
        self.symbol_by_token = {i.instrument_token: i.symbol for i in items}
        return items

    def tokens_for(self, symbols: set[str]) -> list[int]:
        return [self._tokens[s] for s in symbols if s in self._tokens]

    async def _token(self, symbol: str) -> int:
        if symbol.upper() not in self._tokens:
            await self.list_instruments()
        token = self._tokens.get(symbol.upper())
        if token is None:
            raise ValueError(f"Unknown Kite symbol: {symbol}")
        return token

    async def get_historical(self, symbol: str, interval: str, frm: int, to: int) -> list[Candle]:
        token = await self._token(symbol)
        return await asyncio.to_thread(self._history_sync, token, interval, frm, to)

    def _history_sync(self, token: int, interval: str, frm: int, to: int) -> list[Candle]:
        kite_interval, max_days = _KITE_INTERVALS.get(interval, ("minute", 60))
        candles: list[Candle] = []
        for start, end in chunk_ranges(frm, to, max_days):
            self._limiter.wait()
            # Kite takes IST wall-clock times; send naive IST datetimes.
            rows = self._kite.historical_data(
                token, ms_to_ist(start).replace(tzinfo=None), ms_to_ist(end).replace(tzinfo=None), kite_interval
            )
            for r in rows:
                candles.append(Candle(timestamp=to_ms(r["date"]), open=float(r["open"]), high=float(r["high"]),
                                      low=float(r["low"]), close=float(r["close"]), volume=float(r["volume"])))
        return dedupe_sorted(candles, frm, to)

    async def stream_ticks(self, symbols: list[str]) -> AsyncIterator[Tick]:
        if self._feed is None:
            raise RuntimeError("Live feed needs ZERODHA_API_KEY and ZERODHA_ACCESS_TOKEN")
        if not self._tokens:
            await self.list_instruments()
        async for tick in self._feed.stream([s.upper() for s in symbols]):
            yield tick

    def status(self) -> dict:
        return self._feed.status() if self._feed else {"feed": "kiteticker (history only)"}

    async def close(self) -> None:
        if self._feed is not None:
            await self._feed.aclose()


# Backwards-compatible name used by older imports.
KiteTickerProvider = KiteConnectProvider
