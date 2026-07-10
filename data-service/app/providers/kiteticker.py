"""Future drop-in: official Zerodha KiteTicker websocket (true tick stream).

This requires a paid Kite Connect subscription (api_key/access_token) rather than an
enctoken. It is intentionally a stub: it conforms to MarketDataProvider so it can be
selected with ``MARKET_DATA_PROVIDER=kiteticker`` once credentials and the
``kiteconnect`` dependency are added, with no changes elsewhere in the stack.

Historical/instrument calls delegate to the enctoken provider; only ``stream_ticks``
would be replaced by a real websocket subscription (kiteconnect.KiteTicker).
"""
from __future__ import annotations

from typing import AsyncIterator

from app.models import Tick
from app.providers.base import MarketDataProvider
from app.providers.zerodha import ZerodhaEnctokenProvider


class KiteTickerProvider(MarketDataProvider):
    name = "kiteticker"

    def __init__(self, enctoken: str | None) -> None:
        # Reuse enctoken-based history/instruments; swap the stream for a real socket.
        self._fallback = ZerodhaEnctokenProvider(enctoken=enctoken)

    async def list_instruments(self):
        return await self._fallback.list_instruments()

    async def get_historical(self, symbol, interval, frm, to):
        return await self._fallback.get_historical(symbol, interval, frm, to)

    def stream_ticks(self, symbols: list[str]) -> AsyncIterator[Tick]:
        raise NotImplementedError(
            "KiteTicker websocket streaming is not yet wired. "
            "Add the `kiteconnect` dependency and a KiteTicker subscription here, "
            "then this provider becomes a drop-in true-tick source."
        )
