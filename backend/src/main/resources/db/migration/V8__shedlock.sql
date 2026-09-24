-- ShedLock table: guarantees scheduled jobs run on one replica at a time (cluster mode).
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
