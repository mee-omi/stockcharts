"""Minimal in-memory paper broker for the data service's own API.

The app does not use it: when the broker is "mock", the backend routes orders to its own paper
broker (live-price fills, charges, margin, persistence; see backend ``paper`` package).

No credentials needed. MARKET orders are filled immediately at the mock seed price.
LIMIT orders are filled if the limit price is within 1 % of the seed price, otherwise
queued as OPEN. Good for UI/strategy testing without real money.
"""
from __future__ import annotations

import time
import uuid

from app.providers.nse_universe import NSE_STOCKS
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

_SEED_PRICE: dict[str, float] = {s: float(p) for (s, _n, _t, p) in NSE_STOCKS}
_PAPER_CASH = 1_000_000.0  # ₹10 lakh starting balance


class MockTradingProvider(TradingProvider):
    name = "mock"

    def __init__(self) -> None:
        self._orders: list[Order] = []
        self._positions: dict[str, Position] = {}  # "SYMBOL:PRODUCT"
        self._holdings: list[Holding] = []
        self._cash = _PAPER_CASH

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _seed_price(self, symbol: str) -> float:
        return _SEED_PRICE.get(symbol.upper(), 100.0)

    def _fill_price(self, req: PlaceOrderRequest) -> float:
        """Pick the fill price based on order type."""
        if req.order_type == OrderType.MARKET:
            return self._seed_price(req.symbol)
        if req.order_type in (OrderType.LIMIT, OrderType.SL) and req.price:
            return req.price
        if req.order_type == OrderType.SL_M and req.trigger_price:
            return req.trigger_price
        return self._seed_price(req.symbol)

    def _should_fill(self, req: PlaceOrderRequest) -> bool:
        """Immediately fill MARKET orders and LIMIT orders near the seed price."""
        if req.order_type == OrderType.MARKET:
            return True
        seed = self._seed_price(req.symbol)
        if req.price:
            return abs(req.price - seed) / seed < 0.01  # within 1 %
        return False

    # ------------------------------------------------------------------
    # TradingProvider interface
    # ------------------------------------------------------------------

    async def place_order(self, req: PlaceOrderRequest) -> Order:
        fill = self._should_fill(req)
        fill_price = self._fill_price(req)
        status = OrderStatus.COMPLETE if fill else OrderStatus.OPEN
        filled_qty = req.quantity if fill else 0
        avg_price = fill_price if fill else 0.0

        order = Order(
            order_id=str(uuid.uuid4())[:12],
            symbol=req.symbol.upper(),
            exchange=req.exchange,
            transaction_type=req.transaction_type,
            order_type=req.order_type,
            product=req.product,
            quantity=req.quantity,
            price=req.price or fill_price,
            trigger_price=req.trigger_price,
            status=status,
            filled_quantity=filled_qty,
            average_price=avg_price,
            timestamp=int(time.time() * 1000),
        )
        self._orders.append(order)

        if fill:
            self._apply_fill(order)

        return order

    def _apply_fill(self, order: Order) -> None:
        """Update positions and cash after a fill."""
        is_buy = order.transaction_type == TransactionType.BUY
        cost = order.average_price * order.filled_quantity
        if is_buy:
            self._cash -= cost
        else:
            self._cash += cost

        key = f"{order.symbol}:{order.product.value}"
        pos = self._positions.get(key)
        qty_delta = order.filled_quantity if is_buy else -order.filled_quantity

        if pos is None:
            self._positions[key] = Position(
                symbol=order.symbol,
                exchange=order.exchange,
                product=order.product,
                quantity=qty_delta,
                buy_quantity=order.filled_quantity if is_buy else 0,
                sell_quantity=0 if is_buy else order.filled_quantity,
                average_price=order.average_price,
                last_price=order.average_price,
                pnl=0.0,
            )
        else:
            new_qty = pos.quantity + qty_delta
            if new_qty == 0:
                del self._positions[key]
                return
            if is_buy and pos.buy_quantity + order.filled_quantity > 0:
                total_cost = (
                    pos.average_price * pos.buy_quantity
                    + order.average_price * order.filled_quantity
                )
                pos.buy_quantity += order.filled_quantity
                pos.average_price = total_cost / pos.buy_quantity
            else:
                pos.sell_quantity += order.filled_quantity
            pos.quantity = new_qty
            pos.last_price = order.average_price
            pos.pnl = (pos.last_price - pos.average_price) * pos.quantity

    async def cancel_order(self, order_id: str) -> bool:
        for o in self._orders:
            if o.order_id == order_id and o.status == OrderStatus.OPEN:
                o.status = OrderStatus.CANCELLED
                return True
        return False

    async def modify_order(self, order_id: str, req: ModifyOrderRequest) -> Order:
        for o in self._orders:
            if o.order_id == order_id and o.status == OrderStatus.OPEN:
                if req.quantity is not None:
                    o.quantity = req.quantity
                if req.price is not None:
                    o.price = req.price
                if req.trigger_price is not None:
                    o.trigger_price = req.trigger_price
                if req.order_type is not None:
                    o.order_type = req.order_type
                return o
        raise ValueError(f"Order {order_id!r} not found or cannot be modified")

    async def get_orders(self) -> list[Order]:
        return list(reversed(self._orders))

    async def get_positions(self) -> Positions:
        positions = list(self._positions.values())
        for p in positions:
            p.last_price = self._seed_price(p.symbol)
            p.pnl = round((p.last_price - p.average_price) * p.quantity, 2)
        return Positions(net=positions, day=positions)

    async def get_holdings(self) -> list[Holding]:
        # CNC positions that were not squared off become holdings in the real world.
        # In mock, return positions with CNC product as holdings.
        result = []
        for pos in self._positions.values():
            if pos.product == ProductType.CNC and pos.quantity > 0:
                ltp = self._seed_price(pos.symbol)
                pnl = round((ltp - pos.average_price) * pos.quantity, 2)
                pct = round((pnl / (pos.average_price * pos.quantity) * 100), 2) if pos.average_price else 0.0
                result.append(Holding(
                    symbol=pos.symbol,
                    exchange=pos.exchange,
                    quantity=pos.quantity,
                    average_price=pos.average_price,
                    last_price=ltp,
                    pnl=pnl,
                    pnl_percent=pct,
                ))
        return result

    async def get_funds(self) -> Funds:
        used = sum(
            p.average_price * abs(p.quantity)
            for p in self._positions.values()
        )
        return Funds(
            available_cash=round(self._cash, 2),
            used_margin=round(used, 2),
            available_margin=round(self._cash, 2),
            total_balance=round(self._cash + used, 2),
        )
