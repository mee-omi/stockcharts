"""FastAPI market-data and trading service.

Exposes:
  - Market data: instruments, historical candles, live SSE tick stream
  - Trading: orders, positions, holdings, funds
  - Runtime switching of both the market-data provider and the trading broker

All broker-specific logic lives in the providers/ and trading/ packages.
The Spring Boot backend consumes these endpoints — the frontend never talks here directly.
"""
from __future__ import annotations

import json
import logging
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from app.config import settings
from app.providers import AVAILABLE_PROVIDERS, build_provider
from app.providers.base import INTERVALS
from app.trading import AVAILABLE_BROKERS, build_trading_provider
from app.trading.models import (
    Funds,
    Holding,
    ModifyOrderRequest,
    Order,
    PlaceOrderRequest,
    Positions,
)

logger = logging.getLogger(__name__)

provider = None        # MarketDataProvider — set on startup
trading_provider = None  # TradingProvider — set on startup


# ---------------------------------------------------------------------------
# Provider / broker swap helpers
# ---------------------------------------------------------------------------

async def set_active_provider(name: str, enctoken: str | None) -> None:
    """Swap the live market-data provider at runtime."""
    global provider
    new_provider = build_provider(name, enctoken)
    old = provider
    provider = new_provider
    settings.market_data_provider = new_provider.name
    if enctoken is not None:
        settings.enctoken = enctoken
    if old is not None and old is not new_provider:
        try:
            await old.close()
        except Exception:  # noqa: BLE001
            pass


async def set_active_broker(name: str, **credentials: str) -> None:
    """Swap the live trading broker at runtime."""
    global trading_provider
    new_broker = build_trading_provider(name, **credentials)
    old = trading_provider
    trading_provider = new_broker
    settings.trading_broker = new_broker.name
    if old is not None and old is not new_broker:
        try:
            await old.close()
        except Exception:  # noqa: BLE001
            pass


# ---------------------------------------------------------------------------
# Lifespan
# ---------------------------------------------------------------------------

@asynccontextmanager
async def lifespan(app: FastAPI):
    global provider, trading_provider
    provider = build_provider()
    try:
        trading_provider = build_trading_provider()
    except Exception as exc:  # noqa: BLE001
        logger.warning("Trading provider init failed (%s); falling back to mock.", exc)
        from app.trading.mock import MockTradingProvider
        trading_provider = MockTradingProvider()
    yield
    if provider is not None:
        await provider.close()
    if trading_provider is not None:
        await trading_provider.close()


app = FastAPI(
    title="StockCharts Data Service",
    version="2.0.0",
    description="Multi-broker NSE market-data and trading service with pluggable providers.",
    lifespan=lifespan,
)


# ---------------------------------------------------------------------------
# Health
# ---------------------------------------------------------------------------

@app.get("/health")
async def health() -> dict:
    return {
        "status": "ok",
        "provider": provider.name if provider else None,
        "broker": trading_provider.name if trading_provider else None,
    }


# ---------------------------------------------------------------------------
# Market-data provider management
# ---------------------------------------------------------------------------

class SetProviderRequest(BaseModel):
    provider: str
    enctoken: str | None = None


@app.get("/api/provider")
async def get_provider() -> dict:
    return {"active": provider.name if provider else None, "available": AVAILABLE_PROVIDERS}


@app.post("/api/provider")
async def post_provider(req: SetProviderRequest) -> dict:
    try:
        await set_active_provider(req.provider, req.enctoken)
    except ValueError as e:
        raise HTTPException(400, str(e))
    except ImportError as e:
        raise HTTPException(400, f"Required package not installed: {e}")
    except Exception as e:  # noqa: BLE001
        raise HTTPException(400, f"Failed to switch provider: {e}")
    return {"active": provider.name, "available": AVAILABLE_PROVIDERS}


# ---------------------------------------------------------------------------
# Market data endpoints
# ---------------------------------------------------------------------------

@app.get("/api/instruments")
async def instruments() -> list[dict]:
    items = await provider.list_instruments()
    return [i.model_dump() for i in items]


@app.get("/api/historical/{symbol}")
async def historical(
    symbol: str,
    interval: str = Query("minute"),
    frm: int = Query(..., alias="from", description="epoch millis (UTC)"),
    to: int = Query(..., description="epoch millis (UTC)"),
) -> list[dict]:
    if interval not in INTERVALS:
        raise HTTPException(400, f"Unsupported interval. Allowed: {sorted(INTERVALS)}")
    if frm >= to:
        raise HTTPException(400, "`from` must be before `to`")
    candles = await provider.get_historical(symbol, interval, frm, to)
    return [c.model_dump() for c in candles]


@app.get("/api/stream/ticks")
async def stream_ticks(
    request: Request,
    symbols: str = Query(..., description="comma-separated symbols, e.g. RELIANCE,TCS"),
) -> StreamingResponse:
    syms = [s.strip().upper() for s in symbols.split(",") if s.strip()]
    if not syms:
        raise HTTPException(400, "At least one symbol is required")

    async def event_gen():
        yield ": connected\n\n"
        last_ping = time.time()
        async for tick in provider.stream_ticks(syms):
            if await request.is_disconnected():
                break
            yield f"data: {json.dumps(tick.model_dump())}\n\n"
            if time.time() - last_ping > 15:
                yield ": ping\n\n"
                last_ping = time.time()

    return StreamingResponse(
        event_gen(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


# ---------------------------------------------------------------------------
# Trading broker management
# ---------------------------------------------------------------------------

class SetBrokerRequest(BaseModel):
    broker: str
    # Zerodha Kite Connect
    api_key: str | None = None
    access_token: str | None = None
    # Angel One SmartAPI
    client_id: str | None = None
    totp_secret: str | None = None


@app.get("/api/broker")
async def get_broker() -> dict:
    return {
        "active": trading_provider.name if trading_provider else None,
        "available": AVAILABLE_BROKERS,
    }


@app.post("/api/broker")
async def post_broker(req: SetBrokerRequest) -> dict:
    creds = {k: v for k, v in req.model_dump().items() if k != "broker" and v is not None}
    try:
        await set_active_broker(req.broker, **creds)
    except (ValueError, NotImplementedError) as e:
        raise HTTPException(400, str(e))
    except ImportError as e:
        raise HTTPException(400, f"Required package not installed: {e}")
    except Exception as e:  # noqa: BLE001
        raise HTTPException(400, f"Failed to switch broker: {e}")
    return {"active": trading_provider.name, "available": AVAILABLE_BROKERS}


# ---------------------------------------------------------------------------
# Trading endpoints
# ---------------------------------------------------------------------------

@app.post("/api/trading/orders", response_model=Order)
async def place_order(req: PlaceOrderRequest) -> Order:
    try:
        return await trading_provider.place_order(req)
    except Exception as e:  # noqa: BLE001
        raise HTTPException(400, str(e))


@app.get("/api/trading/orders", response_model=list[Order])
async def get_orders() -> list[Order]:
    return await trading_provider.get_orders()


@app.delete("/api/trading/orders/{order_id}")
async def cancel_order(order_id: str) -> dict:
    ok = await trading_provider.cancel_order(order_id)
    if not ok:
        raise HTTPException(404, f"Order {order_id!r} not found or already complete")
    return {"cancelled": True, "order_id": order_id}


@app.put("/api/trading/orders/{order_id}", response_model=Order)
async def modify_order(order_id: str, req: ModifyOrderRequest) -> Order:
    try:
        return await trading_provider.modify_order(order_id, req)
    except ValueError as e:
        raise HTTPException(404, str(e))
    except Exception as e:  # noqa: BLE001
        raise HTTPException(400, str(e))


@app.get("/api/trading/positions", response_model=Positions)
async def get_positions() -> Positions:
    return await trading_provider.get_positions()


@app.get("/api/trading/holdings", response_model=list[Holding])
async def get_holdings() -> list[Holding]:
    return await trading_provider.get_holdings()


@app.get("/api/trading/funds", response_model=Funds)
async def get_funds() -> Funds:
    return await trading_provider.get_funds()
