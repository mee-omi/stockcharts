"""Provider factory — builds a MarketDataProvider by name (or from config)."""
from __future__ import annotations

from app.config import settings
from app.providers.base import MarketDataProvider

AVAILABLE_PROVIDERS = ["mock", "zerodha", "kiteticker", "groww", "angelone"]


def build_provider(name: str | None = None, enctoken: str | None = None) -> MarketDataProvider:
    name = (name or settings.market_data_provider or "mock").lower()
    token = enctoken if enctoken is not None else settings.enctoken
    if name == "mock":
        from app.providers.mock import MockProvider

        return MockProvider()
    if name == "zerodha":
        from app.providers.zerodha import ZerodhaEnctokenProvider

        return ZerodhaEnctokenProvider(enctoken=token)
    if name == "kiteticker":
        from app.providers.kiteticker import KiteTickerProvider

        return KiteTickerProvider(enctoken=token)
    if name == "groww":
        from app.providers.groww import GrowwMarketDataProvider

        return GrowwMarketDataProvider()
    if name == "angelone":
        from app.providers.angelone import AngelOneMarketDataProvider

        return AngelOneMarketDataProvider(
            api_key=settings.angelone_api_key or "",
            client_id=settings.angelone_client_id or "",
            totp_secret=settings.angelone_totp_secret or "",
        )
    raise ValueError(f"Unknown provider: {name!r}. Available: {AVAILABLE_PROVIDERS}")
