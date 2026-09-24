import asyncio
from datetime import datetime

import pytest

from app.market import IST, to_ms
from app.providers.angelone import (
    AngelOneMarketDataProvider,
    AngelSession,
    angel_tick_to_tick,
    parse_candle_rows,
    parse_instrument_master,
)
from app.providers.common import DAY_MS
from app.providers.kiteticker import KiteConnectProvider, kite_tick_to_tick

KITE_MASTER = [
    {"instrument_token": 408065, "tradingsymbol": "INFY", "name": "INFOSYS", "exchange": "NSE",
     "segment": "NSE", "instrument_type": "EQ"},
    {"instrument_token": 256265, "tradingsymbol": "NIFTY 50", "name": "NIFTY 50", "exchange": "NSE",
     "segment": "INDICES", "instrument_type": "EQ"},
    {"instrument_token": 1, "tradingsymbol": "INFY26OCTFUT", "name": "INFY", "exchange": "NFO",
     "segment": "NFO-FUT", "instrument_type": "FUT"},
]


# ---------------- Zerodha Kite Connect ----------------

class FakeKite:
    def __init__(self):
        self.calls = []

    def instruments(self, exchange):
        return KITE_MASTER

    def historical_data(self, token, frm, to, interval):
        self.calls.append((token, frm, to, interval))
        return [{"date": frm.replace(tzinfo=IST), "open": 1, "high": 1, "low": 1, "close": 1, "volume": 5}]


def test_kite_history_is_chunked_to_kite_limits_in_ist():
    kite = FakeKite()
    p = KiteConnectProvider(None, None, kite=kite)
    start = to_ms(datetime(2026, 1, 1, 9, 15, tzinfo=IST))
    candles = asyncio.run(p.get_historical("INFY", "minute", start, start + 150 * DAY_MS))
    assert len(kite.calls) == 3  # 60-day windows for 1-minute data
    token, frm, _, interval = kite.calls[0]
    assert token == 408065 and interval == "minute"
    assert frm.tzinfo is None and (frm.hour, frm.minute) == (9, 15)  # naive IST wall clock
    assert candles[0].timestamp == start


def test_kite_ticks_normalise_equity_and_index_packets():
    symbols = {408065: "INFY", 256265: "NIFTY50"}
    ts = datetime(2026, 9, 24, 10, 0, 5)
    eq = kite_tick_to_tick({"instrument_token": 408065, "tradable": True, "last_price": 1500.5,
                            "volume_traded": 12345, "exchange_timestamp": ts}, symbols)
    assert eq.symbol == "INFY" and eq.ltp == 1500.5 and eq.day_volume == 12345
    assert eq.timestamp == int(ts.timestamp() * 1000)
    idx = kite_tick_to_tick({"instrument_token": 256265, "tradable": False, "last_price": 25000.0}, symbols)
    assert idx.symbol == "NIFTY50" and idx.day_volume is None
    assert kite_tick_to_tick({"instrument_token": 9, "last_price": 1}, symbols) is None


# ---------------- Angel One ----------------

ANGEL_MASTER = [
    {"token": "1594", "symbol": "INFY-EQ", "name": "INFY", "exch_seg": "NSE", "instrumenttype": ""},
    {"token": "99926000", "symbol": "Nifty 50", "name": "NIFTY", "exch_seg": "NSE", "instrumenttype": "AMXIDX"},
    {"token": "500209", "symbol": "INFY", "name": "INFY", "exch_seg": "BSE", "instrumenttype": ""},
    {"token": "35000", "symbol": "INFY26OCTFUT", "name": "INFY", "exch_seg": "NFO", "instrumenttype": "FUTSTK"},
    {"token": "1595", "symbol": "INFY-BE", "name": "INFY", "exch_seg": "NSE", "instrumenttype": ""},
]


def test_angel_instrument_master_keeps_nse_equities_and_indices():
    parsed = parse_instrument_master(ANGEL_MASTER)
    assert [(i.symbol, i.instrument_type, tok) for i, tok in parsed] == [
        ("INFY", "EQ", "1594"), ("NIFTY50", "INDEX", "99926000")]


def test_angel_candles_and_ticks_parse():
    candles = parse_candle_rows([["2026-09-24T09:15:00+05:30", 1, 2, 0.5, 1.5, 100], ["bad"]])
    assert len(candles) == 1
    assert candles[0].timestamp == to_ms(datetime(2026, 9, 24, 9, 15, tzinfo=IST))
    tick = angel_tick_to_tick({"token": "1594", "last_traded_price": 150050, "exchange_timestamp": 1790000000000,
                               "volume_trade_for_the_day": 777}, {"1594": "INFY"})
    assert tick.ltp == 1500.5 and tick.day_volume == 777 and tick.timestamp == 1790000000000


class FakeSmart:
    def __init__(self, responses):
        self.responses = list(responses)
        self.logins = 0
        self.params = []
        self.access_token = None

    def generateSession(self, client, pin, totp):
        assert pin == "1234" and len(totp) == 6
        self.logins += 1
        self.access_token = f"jwt{self.logins}"
        return {"status": True, "data": {"clientcode": client}}

    def getfeedToken(self):
        return "feed"

    def getCandleData(self, params):
        self.params.append(dict(params))
        return self.responses.pop(0)


def angel_provider(responses):
    smart = FakeSmart(responses)
    session = AngelSession("key", "C123", "1234", "JBSWY3DPEHPK3PXP", smart=smart)
    return AngelOneMarketDataProvider("", "", "", session=session, master_loader=lambda: ANGEL_MASTER), smart


def test_angel_history_uses_ist_windows_and_relogs_in_on_expired_token():
    ok = {"status": True, "data": [["2026-09-24T09:15:00+05:30", 1, 1, 1, 1, 1]]}
    p, smart = angel_provider([{"status": False, "errorcode": "AG8001", "message": "Invalid Token"}, ok,
                               {"status": False, "message": "No Data Available"}])
    start = to_ms(datetime(2026, 9, 1, 9, 15, tzinfo=IST))
    candles = asyncio.run(p.get_historical("INFY", "minute", start, start + 40 * DAY_MS))
    assert smart.logins == 2  # initial + re-login after AG8001
    assert len(candles) == 1
    first = smart.params[0]
    assert first["symboltoken"] == "1594" and first["interval"] == "ONE_MINUTE"
    assert first["fromdate"] == "2026-09-01 09:15"  # IST wall clock
    assert len(smart.params) == 3  # retry + second 30-day window


def test_angel_history_raises_on_real_errors():
    p, _ = angel_provider([{"status": False, "errorcode": "AB2001", "message": "Internal error"}])
    with pytest.raises(ValueError, match="AB2001"):
        asyncio.run(p.get_historical("INFY", "day", 0, DAY_MS))
