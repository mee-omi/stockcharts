-- Saved screeners and reusable watchlists.
CREATE TABLE IF NOT EXISTS watchlist (
    name    VARCHAR(64)   PRIMARY KEY,
    symbols VARCHAR(4000) NOT NULL
);

CREATE TABLE IF NOT EXISTS screener (
    name           VARCHAR(64)   PRIMARY KEY,
    expression     VARCHAR(2000) NOT NULL,
    universe_type  VARCHAR(16)   NOT NULL,
    universe_value VARCHAR(4000),
    timeframe      VARCHAR(8)    NOT NULL
);
