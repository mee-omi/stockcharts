"""Needs the optional Zerodha extras (pip install -r requirements-zerodha.txt); skipped otherwise."""
import asyncio
from datetime import datetime

import pytest

from app.market import IST, to_ms
from app.providers.zerodha import ZerodhaEnctokenProvider

pd = pytest.importorskip("pandas")
pyzdata = pytest.importorskip("pyzdata")

KITE_MASTER = [
    {"instrument_token": 408065, "tradingsymbol": "INFY", "name": "INFOSYS", "exchange": "NSE",
     "segment": "NSE", "instrument_type": "EQ"},
    {"instrument_token": 256265, "tradingsymbol": "NIFTY 50", "name": "NIFTY 50", "exchange": "NSE",
     "segment": "INDICES", "instrument_type": "EQ"},
]


class FakePyZData:
    def __init__(self):
        self.calls = []

    def instruments(self):
        return pd.DataFrame(KITE_MASTER)

    def get_data(self, token, start, end, interval):
        self.calls.append((token, start, end, interval))
        # PyZData returns naive IST timestamps.
        return pd.DataFrame([
            {"datetime": pd.Timestamp("2026-09-24 09:15:00"), "open": 1, "high": 2, "low": 0.5, "close": 1.5,
             "volume": 10},
            {"datetime": pd.Timestamp("2026-09-24 09:16:00"), "open": 1.5, "high": 2, "low": 1, "close": 1.8,
             "volume": 20},
        ])


def test_enctoken_provider_lists_equities_and_indices_and_converts_ist():
    client = FakePyZData()
    p = ZerodhaEnctokenProvider(enctoken=None, client=client)
    items = asyncio.run(p.list_instruments())
    assert {(i.symbol, i.instrument_type) for i in items} == {("INFY", "EQ"), ("NIFTY50", "INDEX")}

    frm = to_ms(datetime(2026, 9, 24, 9, 0, tzinfo=IST))
    to = to_ms(datetime(2026, 9, 24, 10, 0, tzinfo=IST))
    candles = asyncio.run(p.get_historical("INFY", "minute", frm, to))
    assert candles[0].timestamp == to_ms(datetime(2026, 9, 24, 9, 15, tzinfo=IST))  # not shifted 5h30
    token, start, end, interval = client.calls[0]
    assert token == 408065 and start == end == "2026-09-24"
    assert isinstance(interval, pyzdata.Interval) and interval.value == "minute"


