CREATE TABLE authorizations (
    id                UUID           PRIMARY KEY,
    merchant_id       UUID           NOT NULL,
    authorized_amount NUMERIC(19, 4) NOT NULL CHECK (authorized_amount > 0),
    currency          CHAR(3)        NOT NULL,
    expires_at        TIMESTAMPTZ    NOT NULL,
    status            VARCHAR(20)    NOT NULL,
    status_timestamp  TIMESTAMPTZ    NOT NULL,
    status_reason     TEXT,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_authorizations_merchant_id ON authorizations (merchant_id);
CREATE INDEX idx_authorizations_status      ON authorizations (status);

CREATE TABLE captures (
    id               UUID           PRIMARY KEY,
    authorization_id UUID           NOT NULL REFERENCES authorizations (id) ON DELETE CASCADE,
    amount           NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    status           VARCHAR(20)    NOT NULL,
    submitted_at     TIMESTAMPTZ    NOT NULL,
    completed_at     TIMESTAMPTZ
);

CREATE INDEX idx_captures_authorization_id ON captures (authorization_id);
