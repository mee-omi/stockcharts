# StockCharts — Technical Documentation

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Architecture](#2-architecture)
3. [Backend (Java/Spring Boot)](#3-backend-javaspring-boot)
   - [Packages](#31-packages)
   - [Entities & Database Model](#32-entities--database-model)
   - [REST API Reference](#33-rest-api-reference)
   - [Core Services](#34-core-services)
   - [Technical Analysis Library](#35-technical-analysis-library)
   - [Expression Engine](#36-expression-engine)
   - [WebSocket / STOMP](#37-websocket--stomp)
   - [Configuration](#38-configuration)
4. [Frontend (React/TypeScript)](#4-frontend-reacttypescript)
   - [Components](#41-components)
   - [API Client](#42-api-client)
   - [WebSocket Integration](#43-websocket-integration)
   - [PWA Support](#44-pwa-support)
5. [Data Service (Python/FastAPI)](#5-data-service-pythonfastapi)
   - [Endpoints](#51-endpoints)
   - [Provider Pattern](#52-provider-pattern)
   - [Configuration](#53-configuration)
6. [Database](#6-database)
   - [Schema](#61-schema)
   - [Migrations](#62-migrations)
7. [Infrastructure](#7-infrastructure)
   - [Docker Compose](#71-docker-compose)
   - [Kubernetes (Kustomize)](#72-kubernetes-kustomize)
8. [Development Guide](#8-development-guide)
9. [Deployment Guide](#9-deployment-guide)

---

## 1. Project Overview

**StockCharts** is a full-stack NSE (National Stock Exchange) technical charting platform for live and historical analysis of all NSE stocks. It is a self-hosted, mobile-responsive PWA with zero dependency on paid third-party charting services.

### Key Features

| Feature | Description |
|---------|-------------|
| Multi-timeframe charting | 1m, 5m, 15m, 30m, 1h, 1d |
| Built-in indicators | 12 indicators: SMA, EMA, WMA, RSI, MACD, Bollinger Bands, Stochastic, ATR, ADX, OBV, VWAP, CCI |
| Custom expression indicators | User-defined formulas, e.g. `ema(close,9) - ema(close,21)` |
| Multi-symbol screener | Boolean expression-based filtering with parallel evaluation |
| Live market data | STOMP/WebSocket push from the backend |
| Replay mode | Bar-by-bar historical playback at configurable speed |
| PWA | Installable on iOS/Android; offline shell |
| Hot-swappable data providers | Ships with mock data; switches to real Zerodha data via config |

---

## 2. Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                   React PWA (Vite + TypeScript)                      │
│         Mobile-first UI · installable · offline shell                │
└──────┬───────────────────────────────────┬───────────────────────────┘
       │  REST via axios (/api/v1/*)        │  STOMP/WebSocket (/ws)
       ▼                                   ▼
┌──────────────────────────────────────────────────────────────────────┐
│           Spring Boot Backend  (Java 21 · Port 8080)                 │
│                                                                      │
│  REST controllers  ·  Indicator Engine  ·  Expression Engine        │
│  Screener Engine   ·  STOMP message broker  ·  JPA + Flyway         │
│  OpenAPI docs: /swagger-ui.html                                      │
└────────┬────────────────────────────────────┬────────────────────────┘
         │  REST (WebClient)                  │  JDBC
         ▼                                   ▼
┌────────────────────────────┐  ┌────────────────────────────────────┐
│  Python FastAPI            │  │  PostgreSQL 16 + TimescaleDB        │
│  (Port 8000)               │  │                                    │
│  · MockProvider (default)  │  │  instrument  (symbol master)       │
│  · ZerodhaProvider         │  │  candle      (hypertable, OHLCV)   │
│  · KiteTicker (stub)       │  │  custom_indicator                  │
│                            │  │  screener · watchlist              │
│  /api/instruments          │  │                                    │
│  /api/historical/{symbol}  │──▶                                    │
│  /api/stream/ticks  (SSE)  │  └────────────────────────────────────┘
│  /api/provider             │
└────────────────────────────┘
```

**Design principle:** All broker/provider specifics are isolated in the Python data service. The Java backend is provider-agnostic; switching data sources requires only a configuration change.

---

## 3. Backend (Java/Spring Boot)

**Language:** Java 21  
**Framework:** Spring Boot 3  
**Build tool:** Maven (wrapper included — `./mvnw`)  
**Port:** 8080

### 3.1 Packages

| Package | Responsibility |
|---------|---------------|
| `candle` | OHLCV persistence, read-through cache, auto-backfill from data service |
| `instrument` | NSE stock universe; synced from data service on startup and every 6 hours |
| `indicator` | Indicator catalog, computation engine, custom indicator CRUD |
| `expr` | Expression lexer, parser, AST compiler, and sandboxed evaluator |
| `ta` | Primitive `double[]` technical analysis functions (no external TA lib) |
| `screener` | Boolean screener with parallel `ForkJoinPool` evaluation; watchlists |
| `live` | STOMP broker; tick ingestion from data service SSE; live candle aggregation |
| `marketdata` | `WebClient` bridge to the Python data service |
| `config` | App properties, CORS, WebSocket, OpenAPI configuration |

### 3.2 Entities & Database Model

#### Instrument

```java
@Entity @Table(name = "instrument")
class Instrument {
    @Id String symbol;          // e.g. "RELIANCE"
    String name;                // "Reliance Industries Ltd"
    long instrumentToken;       // Zerodha numeric token
    String exchange;            // "NSE"
}
```

#### Candle

```java
@Entity @Table(name = "candle")
@IdClass(CandleId.class)
class Candle {
    @Id String symbol;
    @Id Timeframe timeframe;    // ONE_MINUTE … ONE_DAY
    @Id Instant openTime;
    double open, high, low, close, volume;
}
```

The `candle` table is a **TimescaleDB hypertable** partitioned on `open_time` for efficient time-range queries and compression.

#### Timeframe Enum

| Code | Interval (data service) | Duration |
|------|------------------------|----------|
| `1m` | `minute` | 1 minute |
| `5m` | `5minute` | 5 minutes |
| `15m` | `15minute` | 15 minutes |
| `30m` | `30minute` | 30 minutes |
| `1h` | `60minute` | 1 hour |
| `1d` | `day` | 1 day |

#### CustomIndicator

```java
@Entity @Table(name = "custom_indicator")
class CustomIndicator {
    @Id String name;            // "ema_spread"
    String expression;          // "ema(close,9) - ema(close,21)"
    boolean overlay;            // true = draw on price chart
    String description;
}
```

#### Screener & Watchlist

```java
@Entity class Screener {
    @Id String name;
    String expression;          // Boolean expression string
    String universeType;        // "index" | "list" | "watchlist" | "all"
    String universeValue;       // e.g. watchlist name or symbol list
    String timeframe;
}

@Entity class Watchlist {
    @Id String name;
    String symbols;             // Comma-separated: "RELIANCE,TCS,INFY"
}
```

### 3.3 REST API Reference

All endpoints are prefixed with `/api/v1`. Interactive docs are available at `/swagger-ui.html`.

#### Candles

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/candles/{symbol}` | Fetch OHLCV bars. Query params: `timeframe`, `from` (epoch ms), `to` (epoch ms). Auto-backfills missing data. |

#### Indicators

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/indicators` | Full catalog of built-in and custom indicators (params, outputs, overlay flag) |
| `POST` | `/indicators/apply` | Compute indicators on a symbol. Body: `{symbol, timeframe, indicators:[{type,params}]}`. Returns candles + aligned series. |

#### Custom Indicators

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/custom-indicators` | List all saved custom indicators |
| `POST` | `/custom-indicators` | Create a custom indicator |
| `PUT` | `/custom-indicators/{name}` | Update a custom indicator |
| `DELETE` | `/custom-indicators/{name}` | Delete a custom indicator |
| `POST` | `/custom-indicators/validate` | Validate expression syntax (no save) |

#### Screener

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/screener/run` | Ad-hoc screener run. Body: `{expression, timeframe, universe, columns, limit}` |
| `GET` | `/screener` | List saved screeners |
| `POST` | `/screener` | Save a screener definition |
| `DELETE` | `/screener/{name}` | Delete a screener |
| `POST` | `/screener/{name}/run` | Execute a saved screener |

#### Watchlists

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/watchlists` | List all watchlists |
| `POST` | `/watchlists` | Create or update a watchlist |
| `DELETE` | `/watchlists/{name}` | Delete a watchlist |

#### Instruments

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/instruments?query=` | Search NSE symbols / names (powers the stock search dropdown) |

#### Data Source

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/datasource` | Current provider and available options |
| `POST` | `/datasource` | Switch provider. Body: `{provider, enctoken?}` |

### 3.4 Core Services

#### CandleService

Read-through cache: queries local DB first, computes gaps, fetches missing intervals from the data service, upserts, and returns a fully contiguous series.

#### InstrumentService

Syncs the full NSE universe from the Python data service on application startup and every 6 hours via a scheduled task. Provides full-text search over `symbol` and `name` for the dropdown.

#### IndicatorEngine

```java
List<IndicatorResult> apply(Timeframe tf, List<Candle> candles, List<IndicatorSpec> specs)
```

Routes each spec to either the built-in `Indicators.*` library or the `CustomIndicatorResolver`. Returns time-aligned output series with metadata (name, overlay flag, line names).

#### ScreenerService

1. Resolves universe to a list of symbols (NIFTY-50 index, custom list, watchlist name, or all NSE — bounded by `maxUniverse`).
2. Loads latest bar for each symbol (parallel).
3. Compiles and evaluates the boolean expression for each symbol via `ExpressionService`.
4. Returns matched symbols with requested column values.

**Parallelism:** `ForkJoinPool` with configurable thread count (default 8).

#### LiveDataService

- Subscribes to the Python data service's SSE tick endpoint on startup.
- Feeds ticks into `TickAggregator` to build live 1-minute candles.
- Broadcasts `LiveUpdate` records on the STOMP topic `/topic/ticks/{symbol}`.
- Auto-reconnects with exponential backoff on connection failure.

### 3.5 Technical Analysis Library

Implemented in `com.stockcharts.ta.Indicators`. All functions operate on primitive `double[]`, produce output arrays of the same length as input, and fill warm-up positions with `Double.NaN`.

| Indicator | Signature | Output |
|-----------|-----------|--------|
| SMA | `sma(close[], period)` | `double[]` |
| EMA | `ema(close[], period)` | `double[]` |
| WMA | `wma(close[], period)` | `double[]` |
| RSI | `rsi(close[], period)` | `double[]` — Wilder's smoothing |
| MACD | `macd(close[], fast, slow, signal)` | `record Macd(macd[], signal[], histogram[])` |
| Bollinger Bands | `bollinger(close[], period, k)` | `record Bands(upper[], middle[], lower[])` |
| Stochastic | `stochastic(h[], l[], c[], period, dPeriod)` | `record Stoch(k[], d[])` |
| ATR | `atr(h[], l[], c[], period)` | `double[]` — Wilder's smoothing |
| ADX | `adx(h[], l[], c[], period)` | `double[]` — Wilder's smoothing |
| OBV | `obv(close[], volume[])` | `double[]` |
| VWAP | `vwap(h[], l[], c[], vol[], period)` | `double[]` |
| CCI | `cci(h[], l[], c[], period)` | `double[]` |

**Helper functions:** `highest()`, `lowest()`, `crossover()`, `crossunder()`, `abs()`, `max()`, `min()`

**PriceSeries** is a column-oriented OHLCV container:

```java
class PriceSeries {
    long[]   time;
    double[] open, high, low, close, volume;
    double[] hl2()    // (high + low) / 2
    double[] hlc3()   // (high + low + close) / 3
    double[] ohlc4()  // (open + high + low + close) / 4
}
```

### 3.6 Expression Engine

Located in `com.stockcharts.expr`. Takes a string expression and compiles it to a reusable `CompiledExpr` that can be evaluated against a `PriceSeries`.

**Supported price fields:** `open`, `high`, `low`, `close`, `volume`, `hl2`, `hlc3`, `ohlc4`

**Supported functions:**

```
sma(src, period)           ema(src, period)           wma(src, period)
rsi(src, period)           macd(src, fast, slow, sig)
bb_upper/middle/lower(src, period, k)
atr(period)                adx(period)
stoch_k(period, dPeriod)   obv()                      vwap(period)
cci(period)
highest(src, period)       lowest(src, period)
crossover(a, b)            crossunder(a, b)
abs(x)                     max(a, b)                  min(a, b)
```

**Supported operators:** `+`, `-`, `*`, `/`, `>`, `<`, `>=`, `<=`, `==`, `!=`, `and`, `or`, `not`

**Safety:** Sandboxed evaluator — no arbitrary code execution; only whitelisted functions and operators are permitted.

### 3.7 WebSocket / STOMP

**Endpoint:** `ws://host:8080/ws` (raw WebSocket) or via SockJS fallback

**Subscription topic:** `/topic/ticks/{symbol}`

**Payload — `LiveUpdate`:**
```json
{
  "symbol": "RELIANCE",
  "time": 1719734400,
  "open": 2500.0,
  "high": 2510.0,
  "low": 2498.0,
  "close": 2505.0,
  "volume": 123456,
  "ltp": 2505.0
}
```

### 3.8 Configuration

**`application.yml`** (production):
```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5432}/${POSTGRES_DB:stockcharts}
    username: ${POSTGRES_USER:stockcharts}
    password: ${POSTGRES_PASSWORD:stockcharts}
  jpa:
    hibernate.ddl-auto: none
  flyway:
    enabled: true
    baseline-on-migrate: true

stockcharts:
  data-service-url: ${DATA_SERVICE_URL:http://localhost:8000}
  live-symbols: RELIANCE,TCS,HDFCBANK,INFY,ICICIBANK
  backfill-days: 5
  screener:
    maxUniverse: 500
    parallelism: 8
    resultLimit: 200
```

**`application-dev.yml`** (local dev with in-memory H2):
```yaml
spring:
  datasource:
    url: jdbc:h2:mem:stockcharts;MODE=PostgreSQL
    driver-class-name: org.h2.Driver
  jpa:
    hibernate.ddl-auto: create-drop
  flyway.enabled: false
  h2.console.enabled: true
```

Activate with `--spring.profiles.active=dev` or `SPRING_PROFILES_ACTIVE=dev`.

---

## 4. Frontend (React/TypeScript)

**Framework:** React 18 + TypeScript 5  
**Build tool:** Vite 5  
**Port (dev):** 5173 (proxies `/api` and `/ws` to backend)  
**Port (prod):** 80 (served by nginx)

### 4.1 Components

#### App.tsx

Root component managing all global state:

| State | Type | Purpose |
|-------|------|---------|
| `view` | `'chart' \| 'screener'` | Active view |
| `symbol` | `string` | Selected NSE symbol |
| `timeframe` | `string` | Active timeframe |
| `catalog` | `CatalogEntry[]` | Available indicators |
| `indicators` | selected indicators | Indicator config applied to chart |
| `data` | `ApplyResponse` | Candles + indicator series |
| `replayOn / playing / speed` | boolean/number | Replay mode state |

#### Chart.tsx

Renders the charting view using **lightweight-charts**:

- **Main pane:** Candlestick series + all overlay indicators (SMA, EMA, Bollinger, etc.)
- **Volume pane:** Color-coded histogram (green = up bar, red = down bar)
- **Oscillator pane:** Second chart instance for non-overlay indicators (RSI, MACD, Stochastic, etc.)
- **Live updates:** WebSocket subscription updates the current bar in real time
- **Replay mode:** `visibleCount` prop renders a progressive slice of bars

#### IndicatorPanel.tsx

- Toggle indicators on/off
- Edit parameters (period, k, etc.)
- Toggle overlay vs. separate pane per indicator
- Syncs state to parent `App`

#### ScreenerView.tsx

- Free-text expression input
- Universe selector: NIFTY-50 index, all NSE, custom symbol list, saved watchlist
- Timeframe selector
- Save / load / delete named screeners
- Results table with custom column values (default: RSI14, SMA50)

#### ConditionBuilder.tsx

Guided builder for creating screener expressions without writing them manually:

- Row-based UI: metric → operator → value/metric
- AND / OR combiner
- Outputs an editable expression string that feeds `ScreenerView`

#### StockSearch.tsx

Async `react-select` dropdown:

- Debounced search against `GET /api/v1/instruments?query=`
- 50-item result limit
- Renders symbol + company name

#### CustomIndicatorManager.tsx

Modal for managing custom indicators:

- Create / edit / delete
- Live expression validation against the backend before saving
- Toggle overlay flag

#### DataSourceModal.tsx

Modal to switch between `mock` and `zerodha` providers:

- Optional `enctoken` input field for Zerodha authentication
- Shows current active provider

#### TimeframeBar.tsx

Horizontal button bar: `1m · 5m · 15m · 30m · 1h · 1d`

#### ReplayControls.tsx

- Play / pause / step-forward buttons
- Scrubber slider
- Speed selector (0.5× to 10×)

### 4.2 API Client

`frontend/src/api/client.ts` — all functions return typed Promises.

```typescript
searchInstruments(query: string): Promise<Instrument[]>
getIndicatorCatalog(): Promise<CatalogEntry[]>
applyIndicators(symbol, timeframe, indicators): Promise<ApplyResponse>
listCustomIndicators(): Promise<CustomIndicator[]>
createCustomIndicator(ci): Promise<CustomIndicator>
updateCustomIndicator(name, ci): Promise<CustomIndicator>
deleteCustomIndicator(name): Promise<void>
validateExpression(expression): Promise<ValidationResult>
listWatchlists(): Promise<Watchlist[]>
saveWatchlist(wl): Promise<Watchlist>
deleteWatchlist(name): Promise<void>
runScreener(req: ScreenerRequest): Promise<ScreenerResult>
listScreeners(): Promise<ScreenerDef[]>
saveScreener(def): Promise<ScreenerDef>
deleteScreener(name): Promise<void>
getDataSource(): Promise<DataSourceInfo>
setDataSource(provider, enctoken?): Promise<DataSourceInfo>
```

**Base URL:** `/api/v1` — resolved as same-origin in production; proxied in dev via Vite config.

### 4.3 WebSocket Integration

`frontend/src/ws/stomp.ts`:

```typescript
subscribeTicks(symbol: string, onTick: (update: LiveUpdate) => void): () => void
```

- Uses `@stomp/stompjs` over SockJS
- Subscribes to `/topic/ticks/{symbol}`
- Returns unsubscribe function (called on component unmount or symbol change)

### 4.4 PWA Support

- **Manifest:** Installable (Add to Home Screen on iOS/Android)
- **Service Worker:** Offline shell with network-first caching via `vite-plugin-pwa`
- **Responsive layout:** Mobile-first CSS
- **LAN access:** Vite dev server binds to `0.0.0.0` for testing from a phone on the same network

### Key Dependencies

| Package | Version | Purpose |
|---------|---------|---------|
| `react` | ^18.3.1 | UI framework |
| `lightweight-charts` | ^4.1.3 | High-performance candlestick charts |
| `axios` | ^1.7.2 | HTTP client |
| `react-select` | ^5.8.0 | Async symbol search dropdown |
| `@stomp/stompjs` | ^7.0.0 | STOMP over WebSocket |
| `sockjs-client` | ^1.6.1 | SockJS fallback transport |
| `vite-plugin-pwa` | ^0.20.0 | Service worker + manifest generation |

---

## 5. Data Service (Python/FastAPI)

**Language:** Python 3.11+  
**Framework:** FastAPI  
**Port:** 8000

### 5.1 Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/health` | Health check — returns `{"status":"ok"}` |
| `GET` | `/api/instruments` | Full NSE instrument list |
| `GET` | `/api/historical/{symbol}` | OHLCV bars. Params: `interval`, `frm` (epoch ms), `to` (epoch ms) |
| `GET` | `/api/stream/ticks` | SSE stream. Param: `symbols` (comma-separated). Emits `Tick` JSON per event |
| `GET` | `/api/provider` | Current provider name and available providers |
| `POST` | `/api/provider` | Switch active provider. Body: `{provider, enctoken?}` |

**Supported `interval` values:** `minute`, `5minute`, `15minute`, `30minute`, `60minute`, `day`

### 5.2 Provider Pattern

All providers implement the abstract base class:

```python
class MarketDataProvider(ABC):
    name: str

    async def list_instruments(self) -> list[Instrument]: ...
    async def get_historical(self, symbol, interval, frm, to) -> list[Candle]: ...
    async def stream_ticks(self, symbols) -> AsyncIterator[Tick]: ...
    async def close(self) -> None: ...
```

#### MockProvider (Default)

- Deterministic random-walk OHLCV seeded per symbol + interval (stable across restarts).
- Streams ticks at 1 Hz via SSE.
- NIFTY-50 stock universe with realistic seed prices.
- **No credentials required.** Ideal for development and demos.

#### ZerodhaEnctokenProvider

- Uses PyZData to access Zerodha Kite APIs.
- Fetches full NSE instrument master via PyZData.
- Retrieves historical OHLCV from Zerodha for any date range.
- Live ticks via polling the latest 1-minute candle every 5 seconds (no Kite Connect subscription needed).
- **Requires:** `enctoken` (Zerodha session token from `kite.zerodha.com`).

#### KiteTicker (Stub)

- Reserved for future implementation with a paid Kite Connect subscription.
- Would provide true low-latency WebSocket ticks directly from Zerodha.

### 5.3 Configuration

Environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `MARKET_DATA_PROVIDER` | `mock` | Active provider: `mock`, `zerodha`, or `kiteticker` |
| `ENCTOKEN` | _(empty)_ | Zerodha session token (required when provider is `zerodha`) |
| `TICK_INTERVAL_SECONDS` | `1.0` | SSE tick emission frequency (mock provider) |
| `ZERODHA_POLL_SECONDS` | `5.0` | Polling cadence for latest candle (zerodha provider) |

---

## 6. Database

**Engine:** PostgreSQL 16 + TimescaleDB 2.17.2  
**Migration tool:** Flyway (auto-runs on backend startup)

### 6.1 Schema

#### `instrument`

```sql
CREATE TABLE instrument (
    symbol           VARCHAR(64)  PRIMARY KEY,
    name             VARCHAR(256) NOT NULL,
    instrument_token BIGINT       NOT NULL,
    exchange         VARCHAR(16)  NOT NULL
);
```

#### `candle`

```sql
CREATE TABLE candle (
    symbol     VARCHAR(64)          NOT NULL,
    timeframe  VARCHAR(16)          NOT NULL,
    open_time  TIMESTAMPTZ          NOT NULL,
    open       DOUBLE PRECISION     NOT NULL,
    high       DOUBLE PRECISION     NOT NULL,
    low        DOUBLE PRECISION     NOT NULL,
    close      DOUBLE PRECISION     NOT NULL,
    volume     DOUBLE PRECISION     NOT NULL,
    PRIMARY KEY (symbol, timeframe, open_time)
);

CREATE INDEX idx_candle_symbol_tf_time ON candle (symbol, timeframe, open_time DESC);
```

Converted to a **TimescaleDB hypertable** partitioned on `open_time` for:
- Efficient time-range queries
- Automatic data compression for older chunks

#### `custom_indicator`

```sql
CREATE TABLE custom_indicator (
    name        VARCHAR(64)    PRIMARY KEY,
    expression  VARCHAR(2000)  NOT NULL,
    overlay     BOOLEAN        NOT NULL DEFAULT FALSE,
    description VARCHAR(512)
);
```

#### `watchlist`

```sql
CREATE TABLE watchlist (
    name    VARCHAR(64)    PRIMARY KEY,
    symbols VARCHAR(4000)  NOT NULL
);
```

#### `screener`

```sql
CREATE TABLE screener (
    name           VARCHAR(64)    PRIMARY KEY,
    expression     VARCHAR(2000)  NOT NULL,
    universe_type  VARCHAR(16)    NOT NULL,
    universe_value VARCHAR(4000),
    timeframe      VARCHAR(8)     NOT NULL
);
```

### 6.2 Migrations

| File | Contents |
|------|----------|
| `V1__init.sql` | `instrument` and `candle` tables; TimescaleDB hypertable; index |
| `V2__custom_indicators.sql` | `custom_indicator` table |
| `V3__screener_watchlist.sql` | `watchlist` and `screener` tables |

Flyway runs automatically on Spring Boot startup. Setting `baseline-on-migrate: true` allows applying migrations to an existing database.

---

## 7. Infrastructure

### 7.1 Docker Compose

**File:** [docker-compose.yml](docker-compose.yml)

Four services:

| Service | Image | Port | Notes |
|---------|-------|------|-------|
| `timescaledb` | `timescale/timescaledb:2.17.2-pg16` | 5432 | Persistent volume `pgdata` |
| `data-service` | Built from `./data-service` | 8000 | Provider controlled by `MARKET_DATA_PROVIDER` env var |
| `backend` | Built from `./backend` | 8080 | Waits for DB health check and data-service start |
| `frontend` | Built from `./frontend` | 8088→80 | nginx; proxies `/api` and `/ws` to backend |

**Makefile shortcuts:**

```bash
make up       # docker compose up --build -d
make down     # docker compose down
make logs     # docker compose logs -f
make ps       # docker compose ps
```

**Switching to Zerodha data:**
```bash
MARKET_DATA_PROVIDER=zerodha ENCTOKEN=<your_token> make up
```

### 7.2 Kubernetes (Kustomize)

**Structure:**
```
k8s/
  base/           # common resources
  overlays/
    dev/          # inherits base (mock provider)
    prod/         # patches for production (Zerodha enctoken secret)
```

**Base resources:**

| File | Contents |
|------|----------|
| `namespace.yaml` | `stockcharts` namespace |
| `config.yaml` | `ConfigMap` (env vars) + `Secret` (passwords, enctoken) |
| `postgres.yaml` | `StatefulSet` for TimescaleDB + `Service` |
| `data-service.yaml` | `Deployment` + `Service` (port 8000) |
| `backend.yaml` | `Deployment` + `Service` (port 8080); health/liveness probes; 512Mi–1Gi memory |
| `frontend.yaml` | `Deployment` + `Service` (port 80) |
| `ingress.yaml` | nginx `Ingress` on `stockcharts.local`; long timeouts for WebSocket |

**Deploy commands:**
```bash
# Dev
kubectl apply -k k8s/overlays/dev

# Production
kubectl apply -k k8s/overlays/prod
```

**Ingress routing** (host: `stockcharts.local`):

| Path | Backend service | Port |
|------|----------------|------|
| `/api/*` | `backend` | 8080 |
| `/ws/*` | `backend` | 8080 |
| `/` | `frontend` | 80 |

---

## 8. Development Guide

### Prerequisites

- Docker + Docker Compose
- Java 21 (for running backend locally without Docker)
- Node.js 20+ (for running frontend locally without Docker)
- Python 3.11+ (for running data service locally without Docker)

### Quick Start with Docker

```bash
# Clone and start all services (mock data provider, no credentials needed)
make up

# Access the app
open http://localhost:8088
```

### Running Services Locally

#### Data Service
```bash
cd data-service
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
```

#### Backend
```bash
cd backend
# Uses H2 in-memory database (no PostgreSQL needed)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

#### Frontend
```bash
cd frontend
npm install
npm run dev
# Opens http://localhost:5173 (proxied to backend on 8080)
```

### Running Tests

```bash
# Backend unit + integration tests
cd backend
./mvnw test

# Individual test class
./mvnw test -Dtest=IndicatorEngineTest
./mvnw test -Dtest=ExpressionServiceTest
./mvnw test -Dtest=BackendIntegrationTest
```

### Enabling Zerodha Data

1. Log in to [kite.zerodha.com](https://kite.zerodha.com) in your browser.
2. From browser DevTools, copy the `enctoken` cookie value.
3. Start services with:
   ```bash
   MARKET_DATA_PROVIDER=zerodha ENCTOKEN=<token> make up
   ```
   Or switch at runtime via **Settings → Data Source** in the UI.

---

## 9. Deployment Guide

### Docker Compose (Self-hosted)

```bash
# Production with real data
MARKET_DATA_PROVIDER=zerodha ENCTOKEN=<token> docker compose up -d

# App is available at http://<server-ip>:8088
```

### Kubernetes

1. Build and push images:
   ```bash
   docker build -t stockcharts/backend:latest ./backend
   docker build -t stockcharts/data-service:latest ./data-service
   docker build -t stockcharts/frontend:latest ./frontend
   docker push stockcharts/backend:latest
   docker push stockcharts/data-service:latest
   docker push stockcharts/frontend:latest
   ```

2. Configure secrets for production. Copy and fill in the example:
   ```bash
   cp k8s/overlays/prod/enctoken.secret.example.yaml k8s/overlays/prod/enctoken.secret.yaml
   # Edit with your actual enctoken
   ```

3. Apply:
   ```bash
   kubectl apply -k k8s/overlays/prod
   ```

4. Add `stockcharts.local` to your `/etc/hosts` or DNS, pointing to the ingress controller IP.

### Environment Variables Reference

| Variable | Service | Default | Description |
|----------|---------|---------|-------------|
| `POSTGRES_HOST` | backend | `localhost` | PostgreSQL host |
| `POSTGRES_PORT` | backend | `5432` | PostgreSQL port |
| `POSTGRES_DB` | backend | `stockcharts` | Database name |
| `POSTGRES_USER` | backend | `stockcharts` | Database user |
| `POSTGRES_PASSWORD` | backend | `stockcharts` | Database password |
| `DATA_SERVICE_URL` | backend | `http://localhost:8000` | Python data service base URL |
| `MARKET_DATA_PROVIDER` | data-service | `mock` | Active provider |
| `ENCTOKEN` | data-service | _(empty)_ | Zerodha session token |
| `TICK_INTERVAL_SECONDS` | data-service | `1.0` | SSE tick frequency (mock) |
| `ZERODHA_POLL_SECONDS` | data-service | `5.0` | Zerodha poll interval |
