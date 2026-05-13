-- Adds a nullable correlation_id column to the outbox table. The value
-- is set by the OutboxWriter from MDC, and Debezium's outbox router is
-- configured (in infra/debezium/outbox-connector.json) to project it
-- as a Kafka header named correlationId.
--
-- Nullable on purpose: server-initiated events (e.g. the expiry
-- scheduler) run outside any HTTP request and have no correlation
-- context. A future Phase 3 ADR can revisit whether server-initiated
-- flows should mint their own trace IDs.

ALTER TABLE outbox
    ADD COLUMN correlation_id VARCHAR(36);
