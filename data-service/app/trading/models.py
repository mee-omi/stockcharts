"""Wire models for the trading API.

These define the JSON contract between the Python trading providers,
the Spring Boot backend, and the React frontend.
"""
from __future__ import annotations

from enum import Enum

from pydantic import BaseModel


class OrderType(str, Enum):
    MARKET = "MARKET"
    LIMIT = "LIMIT"
    SL = "SL"
    SL_M = "SL-M"


class TransactionType(str, Enum):
    BUY = "BUY"
    SELL = "SELL"


class ProductType(str, Enum):
    CNC = "CNC"    # Cash and Carry — delivery
    MIS = "MIS"    # Margin Intraday Squareoff
    NRML = "NRML"  # Normal (F&O)


class OrderStatus(str, Enum):
    PENDING = "PENDING"
    OPEN = "OPEN"
    COMPLETE = "COMPLETE"
    CANCELLED = "CANCELLED"
    REJECTED = "REJECTED"


class PlaceOrderRequest(BaseModel):
    symbol: str
    exchange: str = "NSE"
    transaction_type: TransactionType
    order_type: OrderType
    product: ProductType
    quantity: int
    price: float | None = None           # required for LIMIT / SL
    trigger_price: float | None = None   # required for SL / SL-M
    tag: str | None = None               # optional user tag


class ModifyOrderRequest(BaseModel):
    quantity: int | None = None
    price: float | None = None
    trigger_price: float | None = None
    order_type: OrderType | None = None


class Order(BaseModel):
    order_id: str
    symbol: str
    exchange: str
    transaction_type: TransactionType
    order_type: OrderType
    product: ProductType
    quantity: int
    price: float = 0.0
    trigger_price: float | None = None
    status: OrderStatus
    filled_quantity: int = 0
    average_price: float = 0.0
    timestamp: int  # epoch ms
    message: str = ""


class Position(BaseModel):
    symbol: str
    exchange: str
    product: ProductType
    quantity: int            # net quantity — positive = long
    buy_quantity: int = 0
    sell_quantity: int = 0
    average_price: float
    last_price: float
    pnl: float
    day_change: float = 0.0


class Positions(BaseModel):
    net: list[Position]
    day: list[Position]


class Holding(BaseModel):
    symbol: str
    exchange: str
    quantity: int
    average_price: float
    last_price: float
    pnl: float
    pnl_percent: float


class Funds(BaseModel):
    available_cash: float
    used_margin: float = 0.0
    available_margin: float = 0.0
    total_balance: float = 0.0
