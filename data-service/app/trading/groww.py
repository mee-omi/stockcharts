"""Groww trading provider stub.

Groww does not publish an official broker API for third-party integrations.
This file reserves the interface so it can be implemented once a stable Groww SDK
is available, with no changes needed elsewhere in the codebase.

To implement:
    1. Identify a stable Groww API or SDK (e.g. the unofficial groww-python library).
    2. Replace the NotImplementedError bodies with real API calls.
    3. Add required dependencies to requirements-groww.txt.
    4. Set TRADING_BROKER=groww in environment.
"""
from __future__ import annotations

from app.trading.base import TradingProvider
from app.trading.models import (
    Funds,
    Holding,
    ModifyOrderRequest,
    Order,
    PlaceOrderRequest,
    Positions,
)


class GrowwTradingProvider(TradingProvider):
    name = "groww"

    def __init__(self, api_key: str | None = None) -> None:
        raise NotImplementedError(
            "Groww does not have an official public trading API. "
            "Use TRADING_BROKER=mock for paper trading, or implement this provider "
            "once a stable Groww SDK becomes available."
        )

    async def place_order(self, req: PlaceOrderRequest) -> Order:
        raise NotImplementedError

    async def cancel_order(self, order_id: str) -> bool:
        raise NotImplementedError

    async def modify_order(self, order_id: str, req: ModifyOrderRequest) -> Order:
        raise NotImplementedError

    async def get_orders(self) -> list[Order]:
        raise NotImplementedError

    async def get_positions(self) -> Positions:
        raise NotImplementedError

    async def get_holdings(self) -> list[Holding]:
        raise NotImplementedError

    async def get_funds(self) -> Funds:
        raise NotImplementedError
