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

    # Polling cadence for the enctoken (zerodha) pseudo-stream (latest 1m candle).
    zerodha_poll_seconds: float = 5.0

    # Real brokers only stream during the NSE session (09:15–15:30 IST, weekdays, not holidays).
    # Set false to stream around the clock (the mock provider always streams).
    live_market_hours_only: bool = True
    # Exchange holidays (YYYY-MM-DD, comma separated) — the session calendar skips these days.
    nse_holidays: str = ""

    # Historical API request budgets (requests per second), kept under the brokers' limits.
    kite_history_rps: float = 2.5
    angelone_history_rps: float = 2.5
    # Angel One instrument master (symbol ↔ token map), refreshed daily.
    angelone_instruments_url: str = (
        "https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json"
    )

    # ---- Trading broker ----
    # mock | zerodha | angelone | groww
    trading_broker: str = "mock"

    # Zerodha Kite Connect (api_key + daily access_token via OAuth) — used for trading and for the
    # official `kiteticker` market-data provider (historical API + KiteTicker websocket).
    zerodha_api_key: str | None = None
    zerodha_access_token: str | None = None

    # Angel One SmartAPI (market data and trading). Login needs the client id, the account MPIN
    # and a TOTP generated from the secret shown when enabling TOTP on SmartAPI.
    angelone_api_key: str | None = None
    angelone_client_id: str | None = None
    angelone_pin: str | None = None
    angelone_totp_secret: str | None = None

    # Groww (placeholder — no official trading API yet)
    groww_api_key: str | None = None


settings = Settings()
