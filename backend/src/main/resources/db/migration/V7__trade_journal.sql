-- Executed trades (fills) used for the journal, realised P&L and tax reports.
CREATE TABLE IF NOT EXISTS trade_journal (
    id          BIGSERIAL     PRIMARY KEY,
    order_id    VARCHAR(64),
    broker      VARCHAR(32)   NOT NULL,
    symbol      VARCHAR(64)   NOT NULL,
    exchange    VARCHAR(16)   NOT NULL,
    side        VARCHAR(8)    NOT NULL,
    product     VARCHAR(8)    NOT NULL,
    quantity    INTEGER       NOT NULL,
    price       DOUBLE PRECISION NOT NULL,
    executed_at TIMESTAMPTZ   NOT NULL,
    tag         VARCHAR(64),
    strategy    VARCHAR(64),
    notes       VARCHAR(2000),
    source      VARCHAR(16)   NOT NULL
);

-- A broker order is journalled at most once (manual entries have no order_id).
CREATE UNIQUE INDEX IF NOT EXISTS uq_trade_journal_order ON trade_journal (broker, order_id);
CREATE INDEX IF NOT EXISTS idx_trade_journal_time ON trade_journal (executed_at);
