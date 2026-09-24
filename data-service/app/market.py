"""NSE session calendar and time helpers (IST).

The equity cash session runs 09:15–15:30 IST on weekdays that are not exchange holidays.
Holidays come from ``NSE_HOLIDAYS`` (update it each year from the NSE holiday list).
"""
from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

from app.config import settings

IST = ZoneInfo("Asia/Kolkata")
SESSION_OPEN = time(9, 15)
SESSION_CLOSE = time(15, 30)


def holidays() -> set[date]:
    out: set[date] = set()
    for part in (settings.nse_holidays or "").split(","):
        part = part.strip()
        if part:
            out.add(date.fromisoformat(part))
    return out


def is_trading_day(d: date) -> bool:
    return d.weekday() < 5 and d not in holidays()


def now_ist() -> datetime:
    return datetime.now(tz=IST)


def is_market_open(at: datetime | None = None) -> bool:
    t = (at or now_ist()).astimezone(IST)
    return is_trading_day(t.date()) and SESSION_OPEN <= t.time() < SESSION_CLOSE


def next_open(at: datetime | None = None) -> datetime:
    """The next session open strictly after ``at`` (or now), unless the session is open now."""
    t = (at or now_ist()).astimezone(IST)
    if is_market_open(t):
        return t
    d = t.date()
    if t.time() >= SESSION_OPEN:
        d += timedelta(days=1)
    while not is_trading_day(d):
        d += timedelta(days=1)
    return datetime.combine(d, SESSION_OPEN, tzinfo=IST)


def ms_to_ist(ms: int) -> datetime:
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).astimezone(IST)


def to_ms(dt: datetime) -> int:
    """Epoch ms of an aware datetime; naive datetimes are taken to be IST (Indian broker APIs)."""
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=IST)
    return int(dt.timestamp() * 1000)
