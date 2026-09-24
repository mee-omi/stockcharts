"""Trading provider factory."""
from __future__ import annotations

from app.config import settings
from app.trading.base import TradingProvider

AVAILABLE_BROKERS = ["mock", "zerodha", "angelone", "groww"]


def build_trading_provider(
    name: str | None = None,
    **credentials: str,
) -> TradingProvider:
    """Build a TradingProvider by name, falling back to settings then 'mock'."""
    name = (name or settings.trading_broker or "mock").lower()

    if name == "mock":
        from app.trading.mock import MockTradingProvider
        return MockTradingProvider()

    if name == "zerodha":
        from app.trading.zerodha import ZerodhaKiteTradingProvider
        api_key = credentials.get("api_key") or settings.zerodha_api_key or ""
        access_token = credentials.get("access_token") or settings.zerodha_access_token or ""
        return ZerodhaKiteTradingProvider(api_key=api_key, access_token=access_token)

    if name == "angelone":
        from app.trading.angelone import AngelOneTradingProvider
        return AngelOneTradingProvider(
            api_key=credentials.get("api_key") or settings.angelone_api_key or "",
            client_id=credentials.get("client_id") or settings.angelone_client_id or "",
            totp_secret=credentials.get("totp_secret") or settings.angelone_totp_secret or "",
            pin=credentials.get("pin") or settings.angelone_pin or "",
        )

    if name == "groww":
        from app.trading.groww import GrowwTradingProvider
        return GrowwTradingProvider(api_key=credentials.get("api_key") or settings.groww_api_key)

    raise ValueError(f"Unknown trading broker: {name!r}. Available: {AVAILABLE_BROKERS}")
