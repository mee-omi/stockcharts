"""The pluggable market-data provider contract.

Every data source (mock, Zerodha enctoken, official KiteTicker websocket) implements
this interface so the rest of the service — and the Spring Boot backend — never changes
when the source is swapped via configuration.
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from typing import AsyncIterator

from app.models import Candle, Instrument, Tick

# Supported candle intervals (mirrors PyZData's interval names where possible).
INTERVALS = {"minute", "5minute", "15minute", "30minute", "60minute", "day"}


class MarketDataProvider(ABC):
    """Abstract source of NSE instruments, historical candles and live ticks."""

    name: str = "base"

    @abstractmethod
    async def list_instruments(self) -> list[Instrument]:
        """Return the tradable NSE instrument universe."""

    @abstractmethod
    async def get_historical(
        self, symbol: str, interval: str, frm: int, to: int
    ) -> list[Candle]:
        """OHLCV candles for ``symbol`` between epoch-ms ``frm`` and ``to``."""

    @abstractmethod
    def stream_ticks(self, symbols: list[str]) -> AsyncIterator[Tick]:
        """Yield live ticks for the given symbols until the consumer disconnects."""

    async def close(self) -> None:  # pragma: no cover - optional cleanup hook
        """Release any provider resources (sessions, sockets)."""
        return None
