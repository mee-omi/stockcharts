FROM python:3.12-slim

WORKDIR /app

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

# Core deps (mock provider — no credentials needed).
COPY requirements.txt ./
RUN pip install -r requirements.txt

# Optional broker packs — pass build-args to activate:
#   --build-arg ZERODHA=1        (market data via PyZData/enctoken)
#   --build-arg ZERODHA_TRADE=1  (trading via kiteconnect, paid API key)
#   --build-arg ANGELONE=1       (market data + trading via SmartAPI)
ARG ZERODHA=0
ARG ZERODHA_TRADE=0
ARG ANGELONE=0

COPY requirements-zerodha.txt requirements-zerodha-trade.txt requirements-angelone.txt ./
RUN if [ "$ZERODHA" = "1" ]; then pip install -r requirements-zerodha.txt; fi && \
    if [ "$ZERODHA_TRADE" = "1" ]; then pip install -r requirements-zerodha-trade.txt; fi && \
    if [ "$ANGELONE" = "1" ]; then pip install -r requirements-angelone.txt; fi

COPY app ./app

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/health').status==200 else 1)"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
