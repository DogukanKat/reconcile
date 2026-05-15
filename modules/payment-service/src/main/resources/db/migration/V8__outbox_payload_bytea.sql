-- Phase 3, Option C (ADR-0009): the outbox payload stops being
-- human-readable JSONB and becomes opaque bytes. PaymentAuthorized is
-- now Confluent-framed Avro; the not-yet-modelled event types are JSON
-- bytes. Either way the column holds a byte string Debezium's
-- ByteArrayConverter passes through untouched.
--
-- There is no meaningful JSONB→BYTEA cast (the old JSON text isn't the
-- new wire format), so this drops and re-adds the column rather than
-- pretending to convert. In this single-developer dev stack any
-- unconsumed outbox rows are disposable, so dropping their payload is
-- acceptable. The production-shaped answer — dual-write the new format
-- alongside the old, backfill, then cut over — is documented in
-- ADR-0009; there is no production here to need it.
--
-- The transient DEFAULT lets ADD COLUMN succeed against existing rows;
-- dropping it immediately keeps payload mandatory for every new insert,
-- preserving the V4 NOT NULL contract.

ALTER TABLE outbox DROP COLUMN payload;
ALTER TABLE outbox ADD COLUMN payload BYTEA NOT NULL DEFAULT ''::bytea;
ALTER TABLE outbox ALTER COLUMN payload DROP DEFAULT;
