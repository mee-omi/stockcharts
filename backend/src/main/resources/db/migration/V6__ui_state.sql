-- Small JSON documents persisted for the UI (chart layout, drawings per symbol).
CREATE TABLE IF NOT EXISTS ui_state (
    state_key  VARCHAR(128)  PRIMARY KEY,
    state_value VARCHAR(65536) NOT NULL,
    updated_at TIMESTAMPTZ   NOT NULL
);
