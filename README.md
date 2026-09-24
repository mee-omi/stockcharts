# StockCharts — NSE Technical Charting Platform

A full-stack platform for live and historical technical charting of **all NSE stocks**, sourced
from **Zerodha Kite** via [PyZData](https://github.com/vikassharma545/Historical-Market-data-From-Zerodha),
with its own indicator and expression engine, a **React PWA** frontend, and **Docker + Kubernetes** deployment.

**Features:** multi-timeframe charts (candles, Heikin-Ashi, bars, line, area) with 12 built-in and
custom formula indicators, drawings (trend lines, horizontal levels, Fibonacci), symbol comparison and
bar replay · live watchlists · a multi-stock screener over NSE index universes · price/formula
**alerts** (in-app, browser, Telegram, webhook) · a rule-based **backtester** · paper and live
**trading** (Zerodha, Angel One) · a **trade journal** with realised P&L, Indian charges and a
capital-gains tax estimate.

> Out of the box it runs on a **synthetic mock data provider** — no credentials needed. Point it at
> real Zerodha data by setting an `enctoken` (see [Configuration](#configuration)).

## Architecture

```
   React PWA (Vite + TS, mobile + installable)
        │  REST (axios)        │  STOMP/WebSocket (live ticks)
        ▼                      ▼
 ┌─────────────────────────────────────────────┐
 │  Spring Boot Backend (Java 21)               │
 │   REST API · own indicator lib + expression  │
 │   engine · screener · STOMP · JPA + Flyway   │
 └───────────────┬───────────────┬──────────────┘
        HTTP pull │     SSE stream │            JDBC
                  ▼                ▼               ▼
 ┌──────────────────────────────────┐   ┌──────────────────┐
 │ Python Data Service (FastAPI)    │   │ PostgreSQL +     │
 │  pluggable MarketDataProvider:   │   │ TimescaleDB      │
 │   • mock (default)               │   │ (candle hypertbl)│
 │   • zerodha (PyZData, enctoken)  │   └──────────────────┘
 │   • kiteticker (future)          │──▶ Zerodha Kite
 └──────────────────────────────────┘
```

Data sourcing (and Zerodha specifics) live in the Python service; Java owns the domain API,
indicator math, persistence, and the client-facing live socket. Even in mock mode the entire
HTTP/SSE/STOMP pipeline runs, so switching to real data is configuration-only.

## Tech stack

| Layer | Stack |
|---|---|
| Frontend | React 18, TypeScript, Vite, lightweight-charts, react-select, STOMP/SockJS, PWA |
| Backend | Java 21, Spring Boot 3.3, own `double[]` indicator library + expression engine, Flyway, springdoc/OpenAPI |
| Data service | Python 3.12, FastAPI, PyZData |
| Database | PostgreSQL 16 + TimescaleDB |
| Deploy | Docker (multi-stage), docker-compose, Kubernetes (Kustomize), nginx |

## Quick start (Docker Compose)

```bash
make up        # builds & starts timescaledb + data-service + backend + frontend
```

Then open:
- **Frontend:** http://localhost:3000
- **Swagger UI:** http://localhost:8080/swagger-ui.html
- **Data service:** http://localhost:8000/health

Stop with `make down`. Tail logs with `make logs`.

## Local development (without Docker)

Run each service in its own terminal:

```bash
# 1) Data service
cd data-service
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
MARKET_DATA_PROVIDER=mock uvicorn app.main:app --reload --port 8000

# 2) Backend (needs a PostgreSQL/TimescaleDB at localhost:5432, db/user/pass = stockcharts)
cd backend
./mvnw spring-boot:run

# 3) Frontend (proxies /api and /ws to the backend)
cd frontend
npm install
npm run dev      # http://localhost:5173  (also reachable from a phone on the same LAN)
```

## Configuration

Copy `.env.example` to `.env`. Key settings:

| Variable | Default | Meaning |
|---|---|---|
| `MARKET_DATA_PROVIDER` | `mock` | `mock` (synthetic) or `zerodha` (real, needs `ENCTOKEN`) |
| `ENCTOKEN` | _(empty)_ | Zerodha enctoken — required only for the `zerodha` provider |
| `DATA_SERVICE_URL` | `http://localhost:8000` | Where the backend reaches the data service |
| `POSTGRES_*` | `stockcharts` | DB host/port/name/user/password |
| `TRADING_BROKER` | `mock` | `mock` (paper), `zerodha`, `angelone` (credentials in `.env.example`) |
| `LIVE_SYMBOLS` | 5 large caps | Always streamed; any symbol a client opens is added automatically (cap `LIVE_MAX_SYMBOLS`, 100) |
| `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` | _(empty)_ | Deliver fired alerts to Telegram |
| `ALERTS_WEBHOOK_URL` | _(empty)_ | POST every fired alert as JSON to this URL |
| `RATE_LIMIT_RPM` / `_TRADING_RPM` / `_HEAVY_RPM` | 600 / 60 / 30 | Per-client limits per minute (general / orders / screener+backtest) |
| `BACKEND_PROFILES=json`, `LOG_FORMAT=json` | _(empty)_ | Structured JSON logs (backend / data service) |

### Using real Zerodha data

1. Log in to https://kite.zerodha.com → DevTools → Application → Cookies → copy the `enctoken`.
2. Set `MARKET_DATA_PROVIDER=zerodha` and `ENCTOKEN=<token>` (the token refreshes on each login).
3. Install the optional dependency: `pip install -r data-service/requirements-zerodha.txt`
   (the Docker image: build with `--build-arg ZERODHA=1`).

The `zerodha` provider serves historical/intraday candles directly and emulates a live feed by
polling the latest 1-minute candle. For a true continuous tick stream, implement the
`kiteticker` provider (a stub is already wired) with a paid Kite Connect subscription.

## REST API

Base path `/api/v1` (full schema at `/swagger-ui.html`):

| Method | Path | Description |
|---|---|---|
| GET | `/instruments?query=` | Search instruments by symbol/name (powers the dropdown) |
| GET | `/candles/{symbol}?timeframe=1m&from=&to=` | OHLCV candles (`from`/`to` = epoch millis) |
| GET | `/indicators` | Indicator catalog (types, params, output lines) |
| POST | `/indicators/apply` | Apply indicators to a symbol; returns candles + aligned series |
| WS | `/ws` (STOMP) → `/topic/ticks/{symbol}` | Live tick/candle updates |
| GET/POST/PUT/DELETE | `/custom-indicators` | Manage user-defined expression indicators |
| POST | `/custom-indicators/validate` | Validate an expression without saving |
| POST | `/screener/run` | Run an ad-hoc screener over a universe |
| GET/POST/DELETE | `/screener`, `/screener/{name}/run` | Save / list / run named screeners |
| GET/POST/DELETE | `/watchlists` | Manage reusable symbol lists |
| GET | `/screener/universes` | Named index universes (NIFTY50, BANKNIFTY, NIFTYIT, …) |
| GET | `/quotes?symbols=A,B` | Last price and day change (watchlist) |
| GET/POST/PUT/DELETE | `/alerts`, `/alerts/{id}/status`, `/alerts/events` | Alerts and trigger history; WS topic `/topic/alerts` |
| POST | `/backtest` | Run a rule-based backtest (entry/exit expressions, stop/target, costs) |
| POST | `/backtest/scan` | Backtest a strategy across a universe; rank, filter, latest-bar signals |
| GET/POST/PUT/DELETE | `/journal`, `/journal/sync` | Trade journal (filled orders are recorded automatically) |
| GET | `/portfolio/report`, `/portfolio/report.csv` | Realised P&L (FIFO), charges, STCG/LTCG/intraday, tax estimate |
| GET/PUT/DELETE | `/ui-state/{key}` | Saved chart layout and per-symbol drawings |
| GET/POST | `/broker`, `/trading/*` | Broker switch, orders, positions, holdings, funds |

Timeframes: `1m, 5m, 15m, 30m, 1h, 1d`.
Built-in indicators: `SMA, EMA, WMA, RSI, MACD, BBANDS, STOCH, ATR, ADX, OBV, VWAP, CCI`.

### Indicators, expressions & screeners

Indicators are computed by our own primitive-`double[]` library (`com.stockcharts.ta`) — no
BigDecimal, no external TA dependency (TA4J is not used).

A sandboxed **expression engine** powers custom indicators and screeners. Expressions operate on
price fields (`open/high/low/close/volume/hl2/hlc3/ohlc4`) and functions
(`sma, ema, wma, rsi, stdev, macd, bb_upper/bb_middle/bb_lower, atr, adx, stoch_k, stoch_d, obv,
vwap, cci, highest, lowest, crossover, crossunder, abs, max, min`) with arithmetic, comparison
(`> < >= <= == !=`) and logical (`and or not`) operators.

- **Custom indicators** — e.g. create `ema_spread = ema(close,9) - ema(close,21)`; it then appears in
  the indicator catalog and overlays on the chart like a built-in.
- **Screeners** — a boolean expression evaluated at the latest bar across a universe (NIFTY-50 preset,
  a custom list, a saved watchlist, or all NSE), returning matching stocks. The UI has a guided
  condition builder that generates an editable expression.

Example screener:
```bash
curl -s -X POST http://localhost:8080/api/v1/screener/run \
  -H 'Content-Type: application/json' \
  -d '{"expression":"rsi(close,14) < 30 and close > sma(close,50)","timeframe":"1d",
       "universe":{"type":"index"},"columns":[{"label":"RSI","expression":"rsi(close,14)"}]}'
```

Example:
```bash
curl -s -X POST http://localhost:8080/api/v1/indicators/apply \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"RELIANCE","timeframe":"5m",
       "indicators":[{"type":"EMA","params":{"period":20}},
                     {"type":"RSI","params":{"period":14}}]}'
```

## Kubernetes deployment

Manifests use **Kustomize** (`k8s/base` + `overlays/dev|prod`).

```bash
# Build images and deploy the dev overlay (mock data) to your cluster:
make k8s-deploy
make k8s-status

# Local cluster (kind): load images first
make kind-load

# Production overlay (real Zerodha data):
cp k8s/overlays/prod/enctoken.secret.example.yaml k8s/overlays/prod/enctoken.secret.yaml
# edit it, paste your enctoken, then:
make k8s-deploy-prod
```

The Ingress serves the app at `http://stockcharts.local` (add `127.0.0.1 stockcharts.local`
to `/etc/hosts`), routing `/api` and `/ws` to the backend and `/` to the frontend.

## Mobile & app compatibility

The frontend is a **PWA**: responsive mobile-first layout, an installable manifest, and a service
worker (offline shell). On Android/iOS, open the site and "Add to Home Screen" to install it as an
app. The Vite dev server binds to `0.0.0.0`, so a phone on the same network can load it directly.
For native app-store builds, wrap the PWA with Capacitor (not included).

## Alerts, backtests and the portfolio report

- **Alerts** use the screener's expression language on one symbol/timeframe (price templates such as
  "crosses above 3000" become `crossover(close, 3000)`). They are evaluated every 15 s at the latest
  bar and fire once, or once per bar while true. Delivery: the Alerts page, an in-app toast, a browser
  notification, and optionally Telegram and a webhook.
- **Backtests** read signals at a bar's close and fill at the next bar's open (no look-ahead);
  stop-loss / take-profit are checked against each bar's high/low (stop first if both are touched).
  Costs are a per-leg commission and slippage. Results: equity curve, drawdown, win rate, profit
  factor, Sharpe, exposure and the trade list, which can be plotted on the chart.
- **Backtest scanner** (Backtest → *Scan universe*, `POST /backtest/scan`): runs the same strategy on
  every symbol of an index, watchlist, list or all stocks in parallel, after refreshing stale candles
  so the latest bar is included. Results are ranked (return, profit factor, win rate, Sharpe,
  drawdown…), filtered (min trades / win rate / profit factor) and show each symbol's signal on the
  latest bar — *buy/short at next open*, *sell/cover at next open*, or *holding* with open P&L.
  Optionally re-runs every 5/15/60 minutes while the page is open.
- **Portfolio** matches journalled fills FIFO per symbol and classifies each closed lot as intraday
  (same IST day or MIS), STCG or LTCG (held > 12 months). Charges and the per-financial-year tax
  estimate use the rates listed in the report's "How this is calculated" section — they are
  estimates for record keeping, not tax advice.

## Operations

- **Metrics:** backend `/actuator/prometheus` (HTTP, JVM, `stockcharts_*` screener/backtest/alert/tick
  metrics) and data-service `/metrics`; the Kubernetes pods carry `prometheus.io/*` scrape annotations.
- **Logs:** `BACKEND_PROFILES=json` / `LOG_FORMAT=json` switch to one-JSON-object-per-line logs.
- **Rate limiting:** per client IP token buckets on `/api/**` (HTTP 429 + `Retry-After`).
- **Backups:** `make db-backup` (compose) writes `backups/stockcharts-<UTC time>.dump`, keeping the
  newest 14; `make db-restore FILE=…` restores one (stopping the backend meanwhile). On Kubernetes the
  `db-backup` CronJob dumps nightly at 02:00 IST to the `db-backups` PVC.
- **Multiple backend replicas:** set `CLUSTER_ENABLED=true` (ShedLock keeps scheduled jobs such as
  alert evaluation on one replica), `STOMP_RELAY_ENABLED=true` with a RabbitMQ/ActiveMQ STOMP broker,
  and `LIVE_ENABLED=false` on all but one replica. The data service keeps provider state in memory
  and must stay at one replica.

## Project structure

```
stockcharts/
├── data-service/   FastAPI: providers/ (mock, zerodha, kiteticker, angelone, groww),
│                   trading/ (mock paper broker, zerodha, angelone, groww), metrics, tests/
├── backend/        Spring Boot: instrument, candle, marketdata, live, indicator, expr, ta,
│                   screener, alert, backtest, journal, quote, trading, uistate, config
├── frontend/       React PWA: Chart + toolbar, Watchlist, Screener, Alerts, Backtest,
│                   Portfolio, Trading; src/lib (chart maths), e2e/ (Playwright)
├── k8s/            Kustomize base (incl. backup CronJob) + dev/prod overlays
├── scripts/        backup-db.sh, restore-db.sh
├── .github/workflows/ci.yml
├── docker-compose.yml   Makefile   .env.example
```

## Testing

```bash
make test        # all of the below except e2e
cd backend && ./mvnw test          # indicator/backtest/P&L/rate-limit unit tests + REST integration (H2)
STOCKCHARTS_TEST_PG_URL=jdbc:postgresql://localhost:5432/stockcharts ./mvnw test
                                   # …plus Flyway migrations + entity validation on real Postgres
cd data-service && pip install -r requirements-dev.txt && ruff check app tests && pytest
cd frontend && npm test && npm run build   # vitest unit tests, type-check, production bundle
cd frontend && npx playwright test         # end-to-end UI tests against a mocked API
```

CI (`.github/workflows/ci.yml`) runs all of these on every pull request, plus Kustomize rendering and
Docker image builds.
