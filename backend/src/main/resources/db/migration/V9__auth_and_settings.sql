-- Login accounts (bcrypt hashes) and small persisted settings (auth secret, risk limits).
CREATE TABLE IF NOT EXISTS app_user (
    id            BIGSERIAL     PRIMARY KEY,
    username      VARCHAR(64)   NOT NULL UNIQUE,
    password_hash VARCHAR(100)  NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL,
    last_login_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS app_setting (
    setting_key   VARCHAR(64)   PRIMARY KEY,
    setting_value VARCHAR(4000) NOT NULL,
    updated_at    TIMESTAMPTZ   NOT NULL
);
