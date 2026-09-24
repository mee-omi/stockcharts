"""Wire models shared across providers and the HTTP API.

These define the JSON contract consumed by the Spring Boot backend.
"""
from __future__ import annotations

from pydantic import BaseModel


class Instrument(BaseModel):
    symbol: str            # e.g. "RELIANCE"
    name: str              # e.g. "Reliance Industries Ltd"
    instrument_token: int  # Zerodha instrument token
    exchange: str = "NSE"
    instrument_type: str = "EQ"  # "EQ" (tradable equity) or "INDEX" (non-tradable index)


class Candle(BaseModel):
    # epoch milliseconds (UTC) for the bar's open time
    timestamp: int
    open: float
    high: float
    low: float
    close: float
    volume: float


class Tick(BaseModel):
    symbol: str
    ltp: float            # last traded price
    timestamp: int        # epoch milliseconds (UTC)
    # Volume traded since the previous tick (mock feed). Real feeds leave it 0 and send day_volume.
    volume: float = 0.0
    # Cumulative volume for the day from exchange feeds; the consumer derives increments from it.
    day_volume: float | None = None
    # Polling providers send the whole current 1-minute bar; the consumer replaces its bar with it.
    bar: Candle | None = None
