-- User-defined expression indicators.
CREATE TABLE IF NOT EXISTS custom_indicator (
    name        VARCHAR(64)   PRIMARY KEY,
    expression  VARCHAR(2000) NOT NULL,
    overlay     BOOLEAN       NOT NULL DEFAULT FALSE,
    description VARCHAR(512)
);
