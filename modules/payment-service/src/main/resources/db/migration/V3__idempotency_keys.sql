CREATE TABLE idempotency_keys (
    merchant_id     UUID         NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    response_status SMALLINT,
    response_body   JSONB,
    resource_id     UUID,
    created_at      TIMESTAMPTZ  NOT NULL,
    completed_at    TIMESTAMPTZ,
    PRIMARY KEY (merchant_id, idempotency_key)
);

CREATE INDEX idx_idempotency_keys_created_at
    ON idempotency_keys (created_at);
