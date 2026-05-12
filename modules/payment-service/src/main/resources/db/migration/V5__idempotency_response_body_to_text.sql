-- ADR-0006 promises bit-for-bit replay of the cached response body.
-- JSONB normalizes object key order and whitespace, which breaks that
-- promise. Convert the column to TEXT (opaque blob); the column is
-- never queried by content, only read out and echoed back.

ALTER TABLE idempotency_keys
    ALTER COLUMN response_body TYPE TEXT USING response_body::text;
