"""Angel One (SmartAPI) trading provider.

Install:
    pip install -r requirements-angelone.txt

Environment variables:
    ANGELONE_API_KEY      — SmartAPI key from myaccount.angelone.in
    ANGELONE_CLIENT_ID    — Your Angel One login ID
    ANGELONE_TOTP_SECRET  — TOTP secret from the Angel One TOTP setup page

SmartAPI docs: https://smartapi.angelbroking.com/docs
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
    Position,
    Positions,
    ProductType,
    TransactionType,
)

_ORDER_TYPE_MAP = {
    OrderType.MARKET: "MARKET",
    OrderType.LIMIT: "LIMIT",
    OrderType.SL: "STOPLOSS_LIMIT",
    OrderType.SL_M: "STOPLOSS_MARKET",
}
_PRODUCT_MAP = {
    ProductType.CNC: "DELIVERY",
    ProductType.MIS: "INTRADAY",
    ProductType.NRML: "CARRYFORWARD",
}
_REVERSE_ORDER_TYPE = {v: k for k, v in _ORDER_TYPE_MAP.items()}
_REVERSE_PRODUCT = {v: k for k, v in _PRODUCT_MAP.items()}


def _ms_now() -> int:
    return int(time.time() * 1000)


def _map_smart_status(s: str) -> OrderStatus:
    return {
        "COMPLETE": OrderStatus.COMPLETE,
        "OPEN": OrderStatus.OPEN,
        "PENDING": OrderStatus.PENDING,
        "CANCELLED": OrderStatus.CANCELLED,
        "REJECTED": OrderStatus.REJECTED,
    }.get(s.upper(), OrderStatus.PENDING)


class AngelOneTradingProvider(TradingProvider):
    name = "angelone"

    def __init__(self, api_key: str, client_id: str, totp_secret: str) -> None:
        if not api_key or not client_id or not totp_secret:
            raise ValueError(
                "ANGELONE_API_KEY, ANGELONE_CLIENT_ID, and ANGELONE_TOTP_SECRET "
                "are all required for the Angel One trading provider."
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
        session_data = self._smart.generateSession(client_id, totp=totp)
        if not session_data.get("data"):
            raise ValueError(f"Angel One session failed: {session_data.get('message', 'unknown error')}")
        self._symbol_token_cache: dict[str, str] = {}

    def _token(self, symbol: str) -> str:
        return self._symbol_token_cache.get(symbol.upper(), "")

    async def place_order(self, req: PlaceOrderRequest) -> Order:
        params: dict = {
            "variety": "NORMAL",
            "tradingsymbol": req.symbol.upper(),
            "symboltoken": self._token(req.symbol),
            "transactiontype": req.transaction_type.value,
            "exchange": req.exchange,
            "ordertype": _ORDER_TYPE_MAP[req.order_type],
            "producttype": _PRODUCT_MAP[req.product],
            "duration": "DAY",
            "quantity": str(req.quantity),
            "price": str(req.price or 0),
            "squareoff": "0",
            "stoploss": "0",
            "triggerprice": str(req.trigger_price or 0),
        }
        if req.tag:
            params["ordertag"] = req.tag

        result = await asyncio.to_thread(self._smart.placeOrder, params)
        order_id = (result.get("data") or {}).get("orderid", "")
        return Order(
            order_id=str(order_id),
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
            message=(result.get("message") or ""),
        )

    async def cancel_order(self, order_id: str) -> bool:
        try:
            params = {"variety": "NORMAL", "orderid": order_id}
            await asyncio.to_thread(self._smart.cancelOrder, params)
            return True
        except Exception:
            return False

    async def modify_order(self, order_id: str, req: ModifyOrderRequest) -> Order:
        # Angel One modify — build params from current order + overrides
        orders = await self.get_orders()
        current = next((o for o in orders if o.order_id == order_id), None)
        if not current:
            raise ValueError(f"Order {order_id!r} not found")

        params: dict = {
            "variety": "NORMAL",
            "orderid": order_id,
            "ordertype": _ORDER_TYPE_MAP[req.order_type or current.order_type],
            "producttype": _PRODUCT_MAP[current.product],
            "duration": "DAY",
            "quantity": str(req.quantity or current.quantity),
            "price": str(req.price or current.price),
            "triggerprice": str(req.trigger_price or current.trigger_price or 0),
        }
        await asyncio.to_thread(self._smart.modifyOrder, params)
        for o in await self.get_orders():
            if o.order_id == order_id:
                return o
        raise ValueError(f"Order {order_id!r} not found after modification")

    async def get_orders(self) -> list[Order]:
        raw_resp = await asyncio.to_thread(self._smart.orderBook)
        result = []
        for o in (raw_resp.get("data") or []):
            try:
                result.append(Order(
                    order_id=str(o.get("orderid", "")),
                    symbol=str(o.get("tradingsymbol", "")),
                    exchange=str(o.get("exchange", "NSE")),
                    transaction_type=TransactionType(o.get("transactiontype", "BUY")),
                    order_type=_REVERSE_ORDER_TYPE.get(o.get("ordertype", "MARKET"), OrderType.MARKET),
                    product=_REVERSE_PRODUCT.get(o.get("producttype", "INTRADAY"), ProductType.MIS),
                    quantity=int(o.get("quantity", 0)),
                    price=float(o.get("price", 0)),
                    status=_map_smart_status(o.get("status", "")),
                    filled_quantity=int(o.get("filledshares", 0)),
                    average_price=float(o.get("averageprice", 0)),
                    timestamp=_ms_now(),
                    message=str(o.get("text", "")),
                ))
            except Exception:
                continue
        return result

    async def get_positions(self) -> Positions:
        raw_resp = await asyncio.to_thread(self._smart.position)
        net: list[Position] = []
        for p in (raw_resp.get("data") or []):
            try:
                net.append(Position(
                    symbol=str(p.get("tradingsymbol", "")),
                    exchange=str(p.get("exchange", "NSE")),
                    product=_REVERSE_PRODUCT.get(p.get("producttype", "INTRADAY"), ProductType.MIS),
                    quantity=int(p.get("netqty", 0)),
                    buy_quantity=int(p.get("buyqty", 0)),
                    sell_quantity=int(p.get("sellqty", 0)),
                    average_price=float(p.get("netprice", 0)),
                    last_price=float(p.get("ltp", 0)),
                    pnl=float(p.get("pnl", 0)),
                ))
            except Exception:
                continue
        return Positions(net=net, day=net)

    async def get_holdings(self) -> list[Holding]:
        raw_resp = await asyncio.to_thread(self._smart.holding)
        result = []
        for h in (raw_resp.get("data") or []):
            qty = int(h.get("quantity", 0))
            avg = float(h.get("averageprice", 0))
            ltp = float(h.get("ltp", avg))
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
        raw_resp = await asyncio.to_thread(self._smart.rmsLimit)
        data = raw_resp.get("data") or {}
        return Funds(
            available_cash=float(data.get("availablecash", 0)),
            used_margin=float(data.get("utilisedamount", 0)),
            available_margin=float(data.get("net", 0)),
            total_balance=float(data.get("net", 0)),
        )
