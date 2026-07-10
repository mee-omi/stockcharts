"""Runtime configuration for the market-data and trading service."""
from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Loaded from environment variables (and an optional .env file)."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # ---- Market data provider ----
    # mock | zerodha | kiteticker | groww | angelone
    market_data_provider: str = "mock"

    # Zerodha enctoken — required for market_data_provider == zerodha/kiteticker.
    enctoken: str | None = None

    # How often (seconds) the live tick stream emits an update per symbol.
    tick_interval_seconds: float = 1.0

    # Polling cadence for the zerodha/angelone pseudo-stream (latest 1m candle).
    zerodha_poll_seconds: float = 5.0

    # ---- Trading broker ----
    # mock | zerodha | angelone | groww
    trading_broker: str = "mock"

    # Zerodha Kite Connect trading (api_key + daily access_token via OAuth)
    zerodha_api_key: str | None = None
    zerodha_access_token: str | None = None

    # Angel One SmartAPI trading
    angelone_api_key: str | None = None
    angelone_client_id: str | None = None
    angelone_totp_secret: str | None = None

    # Groww (placeholder — no official trading API yet)
    groww_api_key: str | None = None


settings = Settings()
