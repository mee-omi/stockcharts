"""Abstract trading provider contract.

Every broker (mock, Zerodha Kite, Angel One, Groww) implements this interface
so the rest of the service never changes when the broker is swapped via config.
"""
from __future__ import annotations

from abc import ABC, abstractmethod

from app.trading.models import (
    Funds,
    Holding,
    ModifyOrderRequest,
    Order,
    PlaceOrderRequest,
    Positions,
)


class TradingProvider(ABC):
    """Abstract source of order management and portfolio data."""

    name: str = "base"

    @abstractmethod
    async def place_order(self, req: PlaceOrderRequest) -> Order:
        """Place a new order and return the broker-assigned order record."""

    @abstractmethod
    async def cancel_order(self, order_id: str) -> bool:
        """Cancel an open order. Returns True if cancelled, False if not found/modifiable."""

    @abstractmethod
    async def modify_order(self, order_id: str, req: ModifyOrderRequest) -> Order:
        """Modify an open order (price / qty / type). Returns the updated order."""

    @abstractmethod
    async def get_orders(self) -> list[Order]:
        """Return today's orders (most-recent first)."""

    @abstractmethod
    async def get_positions(self) -> Positions:
        """Return net and day positions."""

    @abstractmethod
    async def get_holdings(self) -> list[Holding]:
        """Return long-term CNC holdings."""

    @abstractmethod
    async def get_funds(self) -> Funds:
        """Return available cash and margin details."""

    async def close(self) -> None:  # optional cleanup hook
        return None
