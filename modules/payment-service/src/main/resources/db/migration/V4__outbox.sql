-- Column names follow Debezium's outbox-event-router SMT defaults
-- (aggregatetype, aggregateid, type, payload) so the connector config
-- in Phase 1 stays minimal. The two timestamps are split on purpose:
-- occurred_at is the domain timestamp the event carries, created_at is
-- the row's persistence time. Debezium reads the row, not the event,
-- so created_at is what drives ordering on the wire.

CREATE TABLE outbox (
    id            UUID         PRIMARY KEY,
    aggregatetype VARCHAR(50)  NOT NULL,
    aggregateid   UUID         NOT NULL,
    type          VARCHAR(50)  NOT NULL,
    payload       JSONB        NOT NULL,
    occurred_at   TIMESTAMPTZ  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_aggregateid ON outbox (aggregateid);
CREATE INDEX idx_outbox_created_at  ON outbox (created_at);
