import asyncio

import pytest

from app.trading.mock import MockTradingProvider
from app.trading.models import ModifyOrderRequest, OrderStatus, OrderType, PlaceOrderRequest, ProductType


def order(symbol="RELIANCE", side="BUY", type_=OrderType.MARKET, qty=10, price=None, product=ProductType.CNC):
    return PlaceOrderRequest(symbol=symbol, transaction_type=side, order_type=type_, product=product,
                             quantity=qty, price=price)


def run(coro):
    return asyncio.run(coro)


def test_market_order_fills_and_moves_cash():
    broker = MockTradingProvider()
    before = run(broker.get_funds()).available_cash
    o = run(broker.place_order(order(qty=10)))
    assert o.status == OrderStatus.COMPLETE and o.filled_quantity == 10
    after = run(broker.get_funds()).available_cash
    assert after == pytest.approx(before - 10 * o.average_price)
    positions = run(broker.get_positions())
    assert positions.net[0].symbol == "RELIANCE" and positions.net[0].quantity == 10
    assert run(broker.get_holdings())[0].quantity == 10  # CNC → holding


def test_far_limit_order_stays_open_and_can_be_modified_and_cancelled():
    broker = MockTradingProvider()
    o = run(broker.place_order(order(type_=OrderType.LIMIT, price=1.0)))
    assert o.status == OrderStatus.OPEN
    modified = run(broker.modify_order(o.order_id, ModifyOrderRequest(quantity=3)))
    assert modified.quantity == 3
    assert run(broker.cancel_order(o.order_id)) is True
    assert run(broker.cancel_order(o.order_id)) is False  # already cancelled
    with pytest.raises(ValueError):
        run(broker.modify_order(o.order_id, ModifyOrderRequest(quantity=1)))


def test_round_trip_closes_position():
    broker = MockTradingProvider()
    run(broker.place_order(order(product=ProductType.MIS, qty=5)))
    run(broker.place_order(order(side="SELL", product=ProductType.MIS, qty=5)))
    assert run(broker.get_positions()).net == []
