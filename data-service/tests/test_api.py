import time


def test_health_reports_active_provider_and_broker(client):
    body = client.get("/health").json()
    assert body == {"status": "ok", "provider": "mock", "broker": "mock"}


def test_instruments_include_types(client):
    items = client.get("/api/instruments").json()
    by_symbol = {i["symbol"]: i for i in items}
    assert by_symbol["RELIANCE"]["instrument_type"] == "EQ"
    assert by_symbol["BANKNIFTY"]["instrument_type"] == "INDEX"


def test_historical_validates_arguments(client):
    now = int(time.time() * 1000)
    # A fixed past session (Thu 21 May 2026, 09:15–15:30 IST): "the last 24h" has no bars on weekends.
    session_open, session_close = 1_779_335_100_000, 1_779_357_600_000
    ok = client.get("/api/historical/INFY", params={"interval": "15minute", "from": session_open, "to": session_close})
    assert ok.status_code == 200 and len(ok.json()) == 25
    bad_interval = client.get("/api/historical/INFY", params={"interval": "2minute", "from": 0, "to": now})
    assert bad_interval.status_code == 400
    bad_range = client.get("/api/historical/INFY", params={"interval": "day", "from": now, "to": now - 1})
    assert bad_range.status_code == 400


def test_provider_switch_rejects_unknown_and_missing_credentials(client):
    assert client.post("/api/provider", json={"provider": "nope"}).status_code == 400
    # zerodha needs an enctoken (and the optional pyzdata package)
    assert client.post("/api/provider", json={"provider": "zerodha"}).status_code == 400
    assert client.get("/api/provider").json()["active"] == "mock"
    assert client.post("/api/provider", json={"provider": "groww"}).json()["active"] == "groww"
    assert client.post("/api/provider", json={"provider": "mock"}).json()["active"] == "mock"


def test_broker_switch_and_order_flow(client):
    assert client.post("/api/broker", json={"broker": "nope"}).status_code == 400
    assert client.post("/api/broker", json={"broker": "mock"}).json()["active"] == "mock"
    placed = client.post("/api/trading/orders", json={
        "symbol": "TCS", "transaction_type": "BUY", "order_type": "MARKET", "product": "MIS", "quantity": 2,
    })
    assert placed.status_code == 200
    order_id = placed.json()["order_id"]
    assert placed.json()["status"] == "COMPLETE"
    assert any(o["order_id"] == order_id for o in client.get("/api/trading/orders").json())
    assert client.delete(f"/api/trading/orders/{order_id}").status_code == 404  # already complete
    funds = client.get("/api/trading/funds").json()
    assert funds["used_margin"] > 0
    invalid = client.post("/api/trading/orders", json={"symbol": "TCS"})
    assert invalid.status_code == 422


def test_metrics_endpoint_labels_by_route_template(client):
    client.get("/api/historical/INFY", params={"interval": "day", "from": 0, "to": 86_400_000 * 3})
    text = client.get("/metrics").text
    assert 'dataservice_http_requests_total{method="GET",route="/api/historical/{symbol}",status="200"}' in text
    assert 'dataservice_active_provider_info{broker="mock",provider="mock"} 1.0' in text
