"""Prometheus metrics and optional JSON logging for the data service."""
from __future__ import annotations

import json
import logging
import os
import time

from fastapi import FastAPI, Request, Response
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Gauge, Histogram, generate_latest

REQUESTS = Counter(
    "dataservice_http_requests_total",
    "HTTP requests handled",
    ["method", "route", "status"],
)
LATENCY = Histogram(
    "dataservice_http_request_duration_seconds",
    "HTTP request latency (streaming responses: time to first byte)",
    ["method", "route"],
)
TICKS = Counter("dataservice_ticks_streamed_total", "Ticks written to SSE clients")
SSE_CLIENTS = Gauge("dataservice_sse_clients", "Open SSE tick streams")
ACTIVE = Gauge(
    "dataservice_active_provider_info",
    "Active market-data provider and trading broker (value is always 1)",
    ["provider", "broker"],
)


def set_active(provider: str | None, broker: str | None) -> None:
    ACTIVE.clear()
    ACTIVE.labels(provider=provider or "none", broker=broker or "none").set(1)


def install(app: FastAPI) -> None:
    """Add the request-metrics middleware and the /metrics endpoint."""

    @app.middleware("http")
    async def _metrics(request: Request, call_next):
        start = time.perf_counter()
        response = await call_next(request)
        # Label by route template (e.g. /api/historical/{symbol}) to keep cardinality bounded.
        route = request.scope.get("route")
        path = getattr(route, "path", "unmatched")
        LATENCY.labels(request.method, path).observe(time.perf_counter() - start)
        REQUESTS.labels(request.method, path, str(response.status_code)).inc()
        return response

    @app.get("/metrics", include_in_schema=False)
    async def metrics() -> Response:
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)


class JsonFormatter(logging.Formatter):
    """One JSON object per line, for log shippers."""

    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "@timestamp": self.formatTime(record, "%Y-%m-%dT%H:%M:%S%z"),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
            "service": "stockcharts-data-service",
        }
        if record.exc_info:
            payload["stack_trace"] = self.formatException(record.exc_info)
        return json.dumps(payload)


def configure_logging() -> None:
    """LOG_FORMAT=json switches app and uvicorn logs to JSON lines; LOG_LEVEL sets the level."""
    level = os.getenv("LOG_LEVEL", "INFO").upper()
    if os.getenv("LOG_FORMAT", "").lower() != "json":
        logging.basicConfig(level=level)
        return
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    for name in ("", "uvicorn", "uvicorn.error", "uvicorn.access"):
        logger = logging.getLogger(name)
        logger.handlers = [handler]
        logger.setLevel(level)
        logger.propagate = False
