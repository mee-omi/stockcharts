"""Angel One (SmartAPI) market-data provider.

Install:
    pip install -r requirements-angelone.txt

Environment variables:
    ANGELONE_API_KEY, ANGELONE_CLIENT_ID, ANGELONE_TOTP_SECRET

SmartAPI provides historical candle data and live price feed for NSE/BSE.
The live stream is implemented as a polling loop (5 s cadence) over the latest
1-minute candle; for a true WebSocket feed, use the SmartWebSocket integration
(not yet implemented here).
"""
from __future__ import annotations

import asyncio
import logging
import time
from typing import AsyncIterator

from app.models import Candle, Instrument, Tick
from app.providers.base import MarketDataProvider

logger = logging.getLogger(__name__)

_INTERVAL_MAP = {
    "minute":    "ONE_MINUTE",
    "5minute":   "FIVE_MINUTE",
    "15minute":  "FIFTEEN_MINUTE",
    "30minute":  "THIRTY_MINUTE",
    "60minute":  "ONE_HOUR",
    "day":       "ONE_DAY",
}


class AngelOneMarketDataProvider(MarketDataProvider):
    name = "angelone"

    def __init__(self, api_key: str, client_id: str, totp_secret: str) -> None:
        if not api_key or not client_id or not totp_secret:
            raise ValueError(
                "ANGELONE_API_KEY, ANGELONE_CLIENT_ID, and ANGELONE_TOTP_SECRET are required."
            )
        try:
            import pyotp  # noqa: F401 — imported to fail fast when the TOTP dependency is missing
            from SmartApi import SmartConnect  # type: ignore
        except ImportError as exc:
            raise ImportError(
                "smartapi-python and pyotp are not installed. "
                "Run: pip install -r requirements-angelone.txt"
            ) from exc

        import pyotp as _pyotp

        totp = _pyotp.TOTP(totp_secret).now()
        self._smart = SmartConnect(api_key=api_key)
        session = self._smart.generateSession(client_id, totp=totp)
        if not session.get("data"):
            raise ValueError(f"Angel One session failed: {session.get('message', 'unknown error')}")
        self._token_cache: dict[str, str] = {}
        logger.info("Angel One market-data provider initialised for client %s", client_id)

    async def list_instruments(self) -> list[Instrument]:
        """
        Angel One provides an instrument master CSV via SmartAPI.
        Full implementation requires parsing the instrument dump.
        Raises NotImplementedError as a clear signal to the operator.
        """
        raise NotImplementedError(
            "list_instruments is not yet implemented for Angel One. "
            "Fetch and parse the SmartAPI instrument master to implement this."
        )

    async def get_historical(
        self, symbol: str, interval: str, frm: int, to: int
    ) -> list[Candle]:
        from datetime import datetime, timezone

        def _fetch() -> list:
            from_dt = datetime.fromtimestamp(frm / 1000, tz=timezone.utc)
            to_dt = datetime.fromtimestamp(to / 1000, tz=timezone.utc)
            params = {
                "exchange": "NSE",
                "symboltoken": self._token_cache.get(symbol.upper(), ""),
                "interval": _INTERVAL_MAP.get(interval, "ONE_MINUTE"),
                "fromdate": from_dt.strftime("%Y-%m-%d %H:%M"),
                "todate": to_dt.strftime("%Y-%m-%d %H:%M"),
            }
            return self._smart.getCandleData(params).get("data") or []

        bars = await asyncio.to_thread(_fetch)
        candles = []
        for bar in bars:
            # bar = [timestamp_str, open, high, low, close, volume]
            try:
                from datetime import datetime

                dt_val = bar[0]
                if isinstance(dt_val, str):
                    ts = int(datetime.fromisoformat(dt_val.replace("Z", "+00:00")).timestamp() * 1000)
                else:
                    ts = int(dt_val) * 1000 if int(dt_val) < 1e12 else int(dt_val)
                candles.append(Candle(
                    timestamp=ts,
                    open=float(bar[1]),
                    high=float(bar[2]),
                    low=float(bar[3]),
                    close=float(bar[4]),
                    volume=float(bar[5]),
                ))
            except Exception:
                continue
        return candles

    async def stream_ticks(self, symbols: list[str]) -> AsyncIterator[Tick]:
        """Poll latest 1-minute candle as a pseudo live stream."""
        while True:
            now = int(time.time() * 1000)
            start = now - 5 * 60_000
            for sym in symbols:
                try:
                    candles = await self.get_historical(sym, "minute", start, now)
                    if candles:
                        last = candles[-1]
                        yield Tick(
                            symbol=sym.upper(),
                            ltp=last.close,
                            timestamp=last.timestamp,
                            volume=last.volume,
                        )
                except Exception:
                    continue
            await asyncio.sleep(5.0)
