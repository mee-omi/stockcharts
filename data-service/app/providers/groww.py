"""Groww market-data provider stub.

Groww does not publish an official public market-data API. This provider delegates
to MockProvider until a stable Groww SDK is available. Swap in real API calls once
an official integration path exists.

Set MARKET_DATA_PROVIDER=groww in environment to activate.
"""
from __future__ import annotations

import logging
from typing import AsyncIterator

from app.models import Candle, Instrument, Tick
from app.providers.base import MarketDataProvider
from app.providers.mock import MockProvider

logger = logging.getLogger(__name__)


class GrowwMarketDataProvider(MarketDataProvider):
    """Groww market data — currently falls back to mock (no official Groww API)."""

    name = "groww"

    def __init__(self) -> None:
        logger.warning(
            "GrowwMarketDataProvider: No official Groww market-data API is available. "
            "Synthetic mock data will be used. Replace with real API calls once a "
            "stable Groww SDK is published."
        )
        self._fallback = MockProvider()

    async def list_instruments(self) -> list[Instrument]:
        return await self._fallback.list_instruments()

    async def get_historical(
        self, symbol: str, interval: str, frm: int, to: int
    ) -> list[Candle]:
        return await self._fallback.get_historical(symbol, interval, frm, to)

    def stream_ticks(self, symbols: list[str]) -> AsyncIterator[Tick]:
        return self._fallback.stream_ticks(symbols)
