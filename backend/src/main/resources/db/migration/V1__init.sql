-- Instrument universe (powers the searchable dropdown).
CREATE TABLE IF NOT EXISTS instrument (
    symbol           VARCHAR(64)  PRIMARY KEY,
    name             VARCHAR(256) NOT NULL,
    instrument_token BIGINT       NOT NULL,
    exchange         VARCHAR(16)  NOT NULL
);

-- OHLCV candles, keyed by (symbol, timeframe, open_time).
CREATE TABLE IF NOT EXISTS candle (
    symbol     VARCHAR(64)  NOT NULL,
    timeframe  VARCHAR(16)  NOT NULL,
    open_time  TIMESTAMPTZ  NOT NULL,
    open       DOUBLE PRECISION NOT NULL,
    high       DOUBLE PRECISION NOT NULL,
    low        DOUBLE PRECISION NOT NULL,
    close      DOUBLE PRECISION NOT NULL,
    volume     DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (symbol, timeframe, open_time)
);

CREATE INDEX IF NOT EXISTS idx_candle_symbol_tf_time
    ON candle (symbol, timeframe, open_time DESC);

-- Convert `candle` to a TimescaleDB hypertable when the extension is available.
-- On plain PostgreSQL this block is a no-op and the regular table is used.
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS timescaledb;
    PERFORM create_hypertable('candle', 'open_time',
                              if_not_exists => TRUE, migrate_data => TRUE);
    RAISE NOTICE 'candle is now a TimescaleDB hypertable';
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'TimescaleDB unavailable; using a regular table (%).', SQLERRM;
END $$;
