"""Zerodha Kite Connect trading provider.

Requires a paid Kite Connect subscription.

Install:
    pip install -r requirements-zerodha-trade.txt

Environment variables:
    ZERODHA_API_KEY      — Kite Connect API key
    ZERODHA_ACCESS_TOKEN — Daily session access token (refresh via Kite OAuth each day)

The access token expires at midnight IST. You can automate the refresh with a small
login script or use the Kite web login flow to obtain a new one each morning.
"""
from __future__ import annotations

import asyncio
import time

from app.trading.base import TradingProvider
from app.trading.models import (
    Funds,
    Holding,
    ModifyOrderRequest,
    Order,
    OrderStatus,
    OrderType,
    PlaceOrderRequest,
    Positions,
    ProductType,
    TransactionType,
)

_ORDER_TYPE_MAP = {
    OrderType.MARKET: "MARKET",
    OrderType.LIMIT: "LIMIT",
    OrderType.SL: "SL",
    OrderType.SL_M: "SL-M",
}
_TXN_MAP = {
    TransactionType.BUY: "BUY",
    TransactionType.SELL: "SELL",
}
_PRODUCT_MAP = {
    ProductType.CNC: "CNC",
    ProductType.MIS: "MIS",
    ProductType.NRML: "NRML",
}


def _ms_now() -> int:
    return int(time.time() * 1000)


def _map_status(s: str) -> OrderStatus:
    return {
        "COMPLETE": OrderStatus.COMPLETE,
        "OPEN": OrderStatus.OPEN,
        "PENDING": OrderStatus.PENDING,
        "CANCELLED": OrderStatus.CANCELLED,
        "REJECTED": OrderStatus.REJECTED,
    }.get(s.upper(), OrderStatus.PENDING)


class ZerodhaKiteTradingProvider(TradingProvider):
    name = "zerodha"

    def __init__(self, api_key: str, access_token: str) -> None:
        if not api_key or not access_token:
            raise ValueError(
                "ZERODHA_API_KEY and ZERODHA_ACCESS_TOKEN are required for Zerodha trading. "
                "Generate a daily access_token via the Kite Connect OAuth flow."
            )
        try:
            from kiteconnect import KiteConnect  # type: ignore
        except ImportError as exc:
            raise ImportError(
                "kiteconnect is not installed. "
                "Run: pip install -r requirements-zerodha-trade.txt"
            ) from exc

        self._kite = KiteConnect(api_key=api_key)
        self._kite.set_access_token(access_token)

    async def place_order(self, req: PlaceOrderRequest) -> Order:
        params: dict = {
            "tradingsymbol": req.symbol.upper(),
            "exchange": req.exchange,
            "transaction_type": _TXN_MAP[req.transaction_type],
            "order_type": _ORDER_TYPE_MAP[req.order_type],
            "product": _PRODUCT_MAP[req.product],
            "quantity": req.quantity,
        }
        if req.price is not None:
            params["price"] = req.price
        if req.trigger_price is not None:
            params["trigger_price"] = req.trigger_price
        if req.tag:
            params["tag"] = req.tag

        result = await asyncio.to_thread(self._kite.place_order, variety="regular", **params)
        order_id = result.get("order_id", "") if isinstance(result, dict) else str(result)
        return Order(
            order_id=order_id,
            symbol=req.symbol.upper(),
            exchange=req.exchange,
            transaction_type=req.transaction_type,
            order_type=req.order_type,
            product=req.product,
            quantity=req.quantity,
            price=req.price or 0.0,
            trigger_price=req.trigger_price,
            status=OrderStatus.PENDING,
            timestamp=_ms_now(),
        )

    async def cancel_order(self, order_id: str) -> bool:
        try:
            await asyncio.to_thread(
                self._kite.cancel_order, variety="regular", order_id=order_id
            )
            return True
        except Exception:
            return False

    async def modify_order(self, order_id: str, req: ModifyOrderRequest) -> Order:
        params: dict = {}
        if req.quantity is not None:
            params["quantity"] = req.quantity
        if req.price is not None:
            params["price"] = req.price
        if req.trigger_price is not None:
            params["trigger_price"] = req.trigger_price
        if req.order_type is not None:
            params["order_type"] = _ORDER_TYPE_MAP[req.order_type]

        await asyncio.to_thread(
            self._kite.modify_order, variety="regular", order_id=order_id, **params
        )
        for o in await self.get_orders():
            if o.order_id == order_id:
                return o
        raise ValueError(f"Order {order_id!r} not found after modification")

    async def get_orders(self) -> list[Order]:
        raw = await asyncio.to_thread(self._kite.orders) or []
        return [
            Order(
                order_id=str(o.get("order_id", "")),
                symbol=str(o.get("tradingsymbol", "")),
                exchange=str(o.get("exchange", "NSE")),
                transaction_type=TransactionType(o.get("transaction_type", "BUY")),
                order_type=OrderType(o.get("order_type", "MARKET")),
                product=ProductType(o.get("product", "MIS")),
                quantity=int(o.get("quantity", 0)),
                price=float(o.get("price", 0)),
                trigger_price=o.get("trigger_price") or None,
                status=_map_status(o.get("status", "")),
                filled_quantity=int(o.get("filled_quantity", 0)),
                average_price=float(o.get("average_price", 0)),
                timestamp=_ms_now(),
                message=str(o.get("status_message", "")),
            )
            for o in raw
        ]

    async def get_positions(self) -> Positions:
        raw = await asyncio.to_thread(self._kite.positions)
        return Positions(
            net=_parse_kite_positions(raw.get("net", [])),
            day=_parse_kite_positions(raw.get("day", [])),
        )

    async def get_holdings(self) -> list[Holding]:
        raw = await asyncio.to_thread(self._kite.holdings) or []
        result = []
        for h in raw:
            qty = int(h.get("quantity", 0))
            avg = float(h.get("average_price", 0))
            ltp = float(h.get("last_price", avg))
            pnl = round((ltp - avg) * qty, 2)
            pct = round(pnl / (avg * qty) * 100, 2) if avg and qty else 0.0
            result.append(Holding(
                symbol=str(h.get("tradingsymbol", "")),
                exchange=str(h.get("exchange", "NSE")),
                quantity=qty,
                average_price=avg,
                last_price=ltp,
                pnl=pnl,
                pnl_percent=pct,
            ))
        return result

    async def get_funds(self) -> Funds:
        raw = await asyncio.to_thread(self._kite.margins)
        equity = raw.get("equity", {})
        return Funds(
            available_cash=float(equity.get("available", {}).get("cash", 0)),
            used_margin=float(equity.get("utilised", {}).get("exposure", 0)),
            available_margin=float(equity.get("available", {}).get("live_balance", 0)),
            total_balance=float(equity.get("net", 0)),
        )


def _parse_kite_positions(raw: list) -> list:
    from app.trading.models import Position, ProductType  # local import avoids circular

    result = []
    for p in raw:
        try:
            result.append(Position(
                symbol=str(p.get("tradingsymbol", "")),
                exchange=str(p.get("exchange", "NSE")),
                product=ProductType(p.get("product", "MIS")),
                quantity=int(p.get("quantity", 0)),
                buy_quantity=int(p.get("buy_quantity", 0)),
                sell_quantity=int(p.get("sell_quantity", 0)),
                average_price=float(p.get("average_price", 0)),
                last_price=float(p.get("last_price", 0)),
                pnl=float(p.get("pnl", 0)),
                day_change=float(p.get("day_change", 0)),
            ))
        except Exception:
            continue
    return result
