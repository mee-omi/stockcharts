-- Backtest scans that run on a schedule (IST) and notify their matches.
CREATE TABLE IF NOT EXISTS scan_schedule (
    id                BIGSERIAL     PRIMARY KEY,
    name              VARCHAR(100)  NOT NULL UNIQUE,
    request_json      VARCHAR(20000) NOT NULL,
    run_times         VARCHAR(200),
    interval_minutes  INTEGER,
    trading_days_only BOOLEAN       NOT NULL,
    only_new          BOOLEAN       NOT NULL,
    top_n             INTEGER       NOT NULL,
    notify            BOOLEAN       NOT NULL,
    enabled           BOOLEAN       NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL,
    next_run_at       TIMESTAMPTZ,
    last_run_at       TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS scan_run (
    id           BIGSERIAL     PRIMARY KEY,
    schedule_id  BIGINT        NOT NULL REFERENCES scan_schedule (id) ON DELETE CASCADE,
    started_at   TIMESTAMPTZ   NOT NULL,
    duration_ms  BIGINT        NOT NULL,
    status       VARCHAR(16)   NOT NULL,
    matched      INTEGER       NOT NULL,
    new_matches  INTEGER       NOT NULL,
    rows_json    VARCHAR(20000),
    message      VARCHAR(1000)
);
CREATE INDEX IF NOT EXISTS idx_scan_run_schedule ON scan_run (schedule_id, started_at DESC);
