-- Instrument type distinguishes tradable equities from (non-tradable) indices.
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS instrument_type VARCHAR(16) NOT NULL DEFAULT 'EQ';
