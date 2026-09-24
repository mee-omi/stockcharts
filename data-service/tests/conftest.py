import os

import pytest
from fastapi.testclient import TestClient

# Tests always run against the credential-free mock provider and paper broker.
os.environ["MARKET_DATA_PROVIDER"] = "mock"
os.environ["TRADING_BROKER"] = "mock"
os.environ["TICK_INTERVAL_SECONDS"] = "0.01"


@pytest.fixture()
def client():
    from app.main import app

    with TestClient(app) as c:  # runs the lifespan (provider + broker set-up)
        yield c
