from datetime import datetime

from app import market
from app.config import settings
from app.market import IST


def at(y, m, d, hh, mm):
    return datetime(y, m, d, hh, mm, tzinfo=IST)


def test_session_hours_weekends_and_holidays(monkeypatch):
    monkeypatch.setattr(settings, "nse_holidays", "2026-10-02")
    assert market.is_market_open(at(2026, 9, 24, 9, 15))          # Thursday open
    assert not market.is_market_open(at(2026, 9, 24, 9, 14))
    assert not market.is_market_open(at(2026, 9, 24, 15, 30))     # close is exclusive
    assert not market.is_market_open(at(2026, 9, 26, 11, 0))      # Saturday
    assert not market.is_market_open(at(2026, 10, 2, 11, 0))      # holiday (Friday)


def test_next_open_skips_weekend_and_holidays(monkeypatch):
    monkeypatch.setattr(settings, "nse_holidays", "2026-10-02")
    assert market.next_open(at(2026, 10, 1, 16, 0)) == at(2026, 10, 5, 9, 15)  # Thu evening → Mon
    assert market.next_open(at(2026, 9, 24, 8, 0)) == at(2026, 9, 24, 9, 15)
    now = at(2026, 9, 24, 10, 0)
    assert market.next_open(now) == now  # already open


def test_naive_datetimes_are_ist():
    assert market.to_ms(datetime(2026, 9, 24, 9, 15)) == market.to_ms(at(2026, 9, 24, 9, 15))
    assert market.ms_to_ist(market.to_ms(at(2026, 9, 24, 9, 15))).hour == 9
