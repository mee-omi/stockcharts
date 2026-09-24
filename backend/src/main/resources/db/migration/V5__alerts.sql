-- Price / expression alerts and their trigger history.
CREATE TABLE IF NOT EXISTS alert (
    id                BIGSERIAL     PRIMARY KEY,
    name              VARCHAR(128)  NOT NULL,
    symbol            VARCHAR(64)   NOT NULL,
    timeframe         VARCHAR(8)    NOT NULL,
    expression        VARCHAR(2000) NOT NULL,
    trigger_mode      VARCHAR(16)   NOT NULL,
    status            VARCHAR(16)   NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL,
    last_triggered_at TIMESTAMPTZ,
    last_bar_time     TIMESTAMPTZ,
    trigger_count     INTEGER       NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_alert_status ON alert (status);

CREATE TABLE IF NOT EXISTS alert_event (
    id           BIGSERIAL     PRIMARY KEY,
    alert_id     BIGINT        NOT NULL,
    alert_name   VARCHAR(128)  NOT NULL,
    symbol       VARCHAR(64)   NOT NULL,
    expression   VARCHAR(2000) NOT NULL,
    price        DOUBLE PRECISION NOT NULL,
    bar_time     TIMESTAMPTZ   NOT NULL,
    triggered_at TIMESTAMPTZ   NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_alert_event_time ON alert_event (triggered_at DESC);
