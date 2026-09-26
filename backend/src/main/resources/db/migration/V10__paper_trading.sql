-- Paper (simulated) trading account, persisted so it survives restarts.
CREATE TABLE IF NOT EXISTS paper_account (
    id            SMALLINT      PRIMARY KEY,
    starting_cash DOUBLE PRECISION NOT NULL,
    cash          DOUBLE PRECISION NOT NULL,
    trading_day   DATE          NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL,
    updated_at    TIMESTAMPTZ   NOT NULL
);

CREATE TABLE IF NOT EXISTS paper_order (
    order_id        VARCHAR(40)   PRIMARY KEY,
    symbol          VARCHAR(64)   NOT NULL,
    exchange        VARCHAR(16)   NOT NULL,
    side            VARCHAR(4)    NOT NULL,
    order_type      VARCHAR(8)    NOT NULL,
    product         VARCHAR(8)    NOT NULL,
    quantity        INTEGER       NOT NULL,
    price           DOUBLE PRECISION,
    trigger_price   DOUBLE PRECISION,
    status          VARCHAR(16)   NOT NULL,
    filled_quantity INTEGER       NOT NULL,
    average_price   DOUBLE PRECISION NOT NULL,
    charges         DOUBLE PRECISION NOT NULL,
    blocked_margin  DOUBLE PRECISION NOT NULL,
    trading_day     DATE          NOT NULL,
    placed_at       TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    message         VARCHAR(255),
    tag             VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_paper_order_status ON paper_order (status);
CREATE INDEX IF NOT EXISTS idx_paper_order_day ON paper_order (trading_day);

-- Today's positions per (symbol, product); CNC ones move to holdings at the next session.
CREATE TABLE IF NOT EXISTS paper_position (
    symbol        VARCHAR(64)   NOT NULL,
    product       VARCHAR(8)    NOT NULL,
    exchange      VARCHAR(16)   NOT NULL,
    quantity      INTEGER       NOT NULL,
    average_price DOUBLE PRECISION NOT NULL,
    buy_quantity  INTEGER       NOT NULL,
    buy_value     DOUBLE PRECISION NOT NULL,
    sell_quantity INTEGER       NOT NULL,
    sell_value    DOUBLE PRECISION NOT NULL,
    realized_pnl  DOUBLE PRECISION NOT NULL,
    charges       DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (symbol, product)
);

CREATE TABLE IF NOT EXISTS paper_holding (
    symbol        VARCHAR(64)   PRIMARY KEY,
    exchange      VARCHAR(16)   NOT NULL,
    quantity      INTEGER       NOT NULL,
    average_price DOUBLE PRECISION NOT NULL,
    updated_at    TIMESTAMPTZ   NOT NULL
);
