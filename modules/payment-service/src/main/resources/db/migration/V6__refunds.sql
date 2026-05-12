CREATE TABLE refunds (
    id               UUID           PRIMARY KEY,
    authorization_id UUID           NOT NULL,
    capture_id       UUID           NOT NULL REFERENCES captures (id) ON DELETE CASCADE,
    amount           NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency         CHAR(3)        NOT NULL,
    status           VARCHAR(20)    NOT NULL,
    status_timestamp TIMESTAMPTZ    NOT NULL,
    status_reason    TEXT,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_refunds_authorization_id ON refunds (authorization_id);
CREATE INDEX idx_refunds_capture_id       ON refunds (capture_id);
