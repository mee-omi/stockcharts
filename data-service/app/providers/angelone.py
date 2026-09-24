"""Angel One (SmartAPI) market-data provider: historical candles + SmartWebSocket V2.

Install: ``pip install -r requirements-angelone.txt``.
Environment: ``ANGELONE_API_KEY``, ``ANGELONE_CLIENT_ID``, ``ANGELONE_PIN`` (account MPIN) and
``ANGELONE_TOTP_SECRET``. The session is created with a fresh TOTP and renewed automatically: each
IST day, and whenever SmartAPI reports an invalid/expired token.

* Instruments: Angel's public instrument master (symbol ↔ token), refreshed daily. NSE equities
  (``-EQ`` series) and NSE indices are exposed.
* History: ``getCandleData`` in windows sized to SmartAPI's per-request limits, paced below its
  rate limit, IST wall-clock times in and out.
* Live: SmartWebSocket V2 in QUOTE mode during market hours. Prices arrive in paise and are
  converted to rupees; ticks carry the day's cumulative volume.
"""
from __future__ import annotations

import asyncio
import logging
import threading
import time
from collections.abc import AsyncIterator
from datetime import datetime

from app.config import settings
from app.market import ms_to_ist, now_ist, to_ms
from app.models import Candle, Instrument, Tick
from app.providers.base import MarketDataProvider
from app.providers.common import BrokerFeed, RateLimiter, chunk_ranges, dedupe_sorted, normalize_symbol

logger = logging.getLogger(__name__)

# Wire interval -> (SmartAPI interval, max days per getCandleData request).
_ANGEL_INTERVALS = {
    "minute": ("ONE_MINUTE", 30),
    "5minute": ("FIVE_MINUTE", 100),
    "15minute": ("FIFTEEN_MINUTE", 200),
    "30minute": ("THIRTY_MINUTE", 200),
    "60minute": ("ONE_HOUR", 400),
    "day": ("ONE_DAY", 2000),
}
# SmartAPI error codes meaning the JWT is invalid or expired.
_AUTH_ERRORS = {"AG8001", "AG8002", "AG8003", "AB1010", "AB8050", "AB8051"}
_NSE_CM = 1  # SmartWebSocket exchange type for NSE cash
_QUOTE_MODE = 2
_INSTRUMENTS_TTL_S = 24 * 3600


def parse_instrument_master(rows: list[dict]) -> list[tuple[Instrument, str]]:
    """(Instrument, SmartAPI token) for NSE equities (-EQ series) and NSE indices."""
    out: list[tuple[Instrument, str]] = []
    for r in rows:
        if str(r.get("exch_seg", "")).upper() != "NSE":
            continue
        symbol = str(r.get("symbol", "")).strip()
        token = str(r.get("token", "")).strip()
        itype = str(r.get("instrumenttype", "")).upper()
        if not symbol or not token:
            continue
        if itype == "AMXIDX":
            inst = Instrument(symbol=normalize_symbol(symbol), name=str(r.get("name") or symbol).strip() or symbol,
                              instrument_token=int(token), exchange="NSE", instrument_type="INDEX")
        elif symbol.endswith("-EQ"):
            base = symbol[: -len("-EQ")]
            inst = Instrument(symbol=base.upper(), name=str(r.get("name") or base), instrument_token=int(token),
                              exchange="NSE", instrument_type="EQ")
        else:
            continue
        out.append((inst, token))
    return out


def parse_candle_rows(rows: list) -> list[Candle]:
    """SmartAPI candle rows: [timestamp (ISO with +05:30), open, high, low, close, volume]."""
    candles = []
    for bar in rows or []:
        try:
            ts = bar[0]
            dt = datetime.fromisoformat(ts.replace("Z", "+00:00")) if isinstance(ts, str) else None
            ms = to_ms(dt) if dt is not None else (int(ts) * 1000 if int(ts) < 1e12 else int(ts))
            candles.append(Candle(timestamp=ms, open=float(bar[1]), high=float(bar[2]), low=float(bar[3]),
                                  close=float(bar[4]), volume=float(bar[5])))
        except (TypeError, ValueError, IndexError):
            continue
    return candles


def angel_tick_to_tick(d: dict, symbol_by_token: dict[str, str]) -> Tick | None:
    """Normalise one SmartWebSocket V2 message (LTP/QUOTE/SNAP_QUOTE) to our Tick."""
    symbol = symbol_by_token.get(str(d.get("token", "")).strip())
    ltp = d.get("last_traded_price")
    if symbol is None or ltp is None:
        return None
    ts = d.get("exchange_timestamp") or int(time.time() * 1000)
    volume = d.get("volume_trade_for_the_day")
    return Tick(symbol=symbol, ltp=round(ltp / 100.0, 2), timestamp=int(ts),
                day_volume=float(volume) if volume is not None else None)


class AngelSession:
    """A SmartAPI login that renews itself: once per IST day and on auth errors."""

    def __init__(self, api_key: str, client_id: str, pin: str, totp_secret: str, smart=None) -> None:
        self.api_key, self.client_id, self._pin, self._totp_secret = api_key, client_id, pin, totp_secret
        self._smart = smart
        self._login_day = None
        self._lock = threading.Lock()
        self.feed_token: str | None = None
        self.jwt: str | None = None

    def _new_client(self):
        from SmartApi import SmartConnect  # type: ignore

        return SmartConnect(api_key=self.api_key)

    def login(self) -> None:
        import pyotp  # type: ignore

        with self._lock:
            smart = self._smart if self._smart is not None else self._new_client()
            result = smart.generateSession(self.client_id, self._pin, pyotp.TOTP(self._totp_secret).now())
            if not result or not result.get("status", bool(result.get("data"))) or not result.get("data"):
                raise ValueError(f"Angel One login failed: {(result or {}).get('message', 'unknown error')}")
            self._smart = smart
            self.jwt = getattr(smart, "access_token", None)
            self.feed_token = smart.getfeedToken()
            self._login_day = now_ist().date()
            logger.info("Angel One session established for %s", self.client_id)

    def client(self):
        if self._smart is None or self._login_day != now_ist().date() or self.jwt is None:
            self.login()
        return self._smart

    def call(self, fn_name: str, *args):
        """Call a SmartConnect method, re-logging in once if the token was rejected."""
        for attempt in (1, 2):
            resp = getattr(self.client(), fn_name)(*args)
            code = str((resp or {}).get("errorcode") or (resp or {}).get("errorCode") or "")
            if code in _AUTH_ERRORS and attempt == 1:
                logger.info("Angel One token rejected (%s); logging in again", code)
                self.login()
                continue
            return resp
        return resp


class _AngelFeed(BrokerFeed):
    def __init__(self, session: AngelSession, provider: "AngelOneMarketDataProvider") -> None:
        super().__init__("angelone-websocket", settings.live_market_hours_only)
        self._session = session
        self._provider = provider
        self._sws = None
        self._opened = threading.Event()

    def _open(self) -> None:
        from SmartApi.smartWebSocketV2 import SmartWebSocketV2  # type: ignore

        self._session.client()  # ensure a fresh login (feed token)
        self._opened.clear()
        sws = SmartWebSocketV2(f"Bearer {self._session.jwt}", self._session.api_key,
                               self._session.client_id, self._session.feed_token,
                               max_retry_attempt=3)
        sws.on_open = lambda wsapp: self._opened.set()
        sws.on_data = self._on_data
        sws.on_error = lambda *a: self.mark_disconnected("Angel One websocket error")
        sws.on_close = lambda wsapp: self.mark_disconnected("Angel One websocket closed")
        threading.Thread(target=sws.connect, name="angelone-ws", daemon=True).start()
        if not self._opened.wait(15):
            sws.close_connection()
            raise ConnectionError("Angel One websocket did not open within 15s")
        self._sws = sws

    def _on_data(self, wsapp, message: dict) -> None:
        tick = angel_tick_to_tick(message, self._provider.symbol_by_token)
        if tick is not None:
            self.publish(tick)

    def _close(self) -> None:
        if self._sws is not None:
            self._sws.close_connection()
            self._sws = None

    def _resubscribe(self, wanted: set[str], current: set[str]) -> None:
        add = self._provider.tokens_for(wanted - current)
        remove = self._provider.tokens_for(current - wanted)
        if remove:
            self._sws.unsubscribe("sc-unsub", _QUOTE_MODE, [{"exchangeType": _NSE_CM, "tokens": remove}])
        if add:
            self._sws.subscribe("sc-sub", _QUOTE_MODE, [{"exchangeType": _NSE_CM, "tokens": add}])


class AngelOneMarketDataProvider(MarketDataProvider):
    name = "angelone"

    def __init__(self, api_key: str, client_id: str, totp_secret: str, pin: str | None = None,
                 session: AngelSession | None = None, master_loader=None) -> None:
        if session is None:
            if not api_key or not client_id or not totp_secret or not pin:
                raise ValueError(
                    "ANGELONE_API_KEY, ANGELONE_CLIENT_ID, ANGELONE_PIN and ANGELONE_TOTP_SECRET are required."
                )
            try:
                import pyotp  # noqa: F401 — fail fast when the TOTP dependency is missing
                from SmartApi import SmartConnect  # type: ignore  # noqa: F401
            except ImportError as exc:
                raise ImportError(
                    "smartapi-python and pyotp are not installed. Run: pip install -r requirements-angelone.txt"
                ) from exc
            session = AngelSession(api_key, client_id, pin, totp_secret)
            session.login()  # surface bad credentials when the provider is selected
        self._session = session
        self._master_loader = master_loader or self._download_master
        self._limiter = RateLimiter(settings.angelone_history_rps)
        self._tokens: dict[str, str] = {}
        self.symbol_by_token: dict[str, str] = {}
        self._instruments: list[Instrument] = []
        self._loaded_at = 0.0
        self._feed = _AngelFeed(session, self)

    @staticmethod
    def _download_master() -> list[dict]:
        import httpx

        resp = httpx.get(settings.angelone_instruments_url, timeout=120)
        resp.raise_for_status()
        return resp.json()

    async def list_instruments(self) -> list[Instrument]:
        if not self._instruments or time.time() - self._loaded_at > _INSTRUMENTS_TTL_S:
            parsed = await asyncio.to_thread(lambda: parse_instrument_master(self._master_loader()))
            self._instruments = [i for i, _ in parsed]
            self._tokens = {i.symbol: tok for i, tok in parsed}
            self.symbol_by_token = {tok: i.symbol for i, tok in parsed}
            self._loaded_at = time.time()
        return self._instruments

    def tokens_for(self, symbols: set[str]) -> list[str]:
        return [self._tokens[s] for s in symbols if s in self._tokens]

    async def _token(self, symbol: str) -> str:
        await self.list_instruments()
        token = self._tokens.get(symbol.upper())
        if token is None:
            raise ValueError(f"Unknown Angel One symbol: {symbol}")
        return token

    async def get_historical(self, symbol: str, interval: str, frm: int, to: int) -> list[Candle]:
        token = await self._token(symbol)
        return await asyncio.to_thread(self._history_sync, token, interval, frm, to)

    def _history_sync(self, token: str, interval: str, frm: int, to: int) -> list[Candle]:
        angel_interval, max_days = _ANGEL_INTERVALS.get(interval, ("ONE_MINUTE", 30))
        candles: list[Candle] = []
        for start, end in chunk_ranges(frm, to, max_days):
            self._limiter.wait()
            params = {
                "exchange": "NSE",
                "symboltoken": token,
                "interval": angel_interval,
                # SmartAPI takes IST wall-clock times.
                "fromdate": ms_to_ist(start).strftime("%Y-%m-%d %H:%M"),
                "todate": ms_to_ist(end).strftime("%Y-%m-%d %H:%M"),
            }
            resp = self._session.call("getCandleData", params) or {}
            if resp.get("status") is False:
                message = str(resp.get("message") or "")
                if "no data" in message.lower():
                    continue  # e.g. a window that falls entirely on holidays
                raise ValueError(f"Angel One candle error {resp.get('errorcode')}: {message}")
            candles.extend(parse_candle_rows(resp.get("data") or []))
        return dedupe_sorted(candles, frm, to)

    async def stream_ticks(self, symbols: list[str]) -> AsyncIterator[Tick]:
        await self.list_instruments()
        async for tick in self._feed.stream([s.upper() for s in symbols]):
            yield tick

    def status(self) -> dict:
        return self._feed.status()

    async def close(self) -> None:
        await self._feed.aclose()
