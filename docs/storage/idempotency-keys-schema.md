# idempotency_keys table

Backing decisions: ADR-0006

## Schema

```sql
CREATE TABLE idempotency_keys (
  merchant_id     UUID         NOT NULL,
  idempotency_key VARCHAR(255) NOT NULL,
  request_hash    CHAR(64)     NOT NULL,
  status          VARCHAR(20)  NOT NULL,
  response_status SMALLINT,
  response_body   TEXT,
  resource_id     UUID,
  created_at      TIMESTAMPTZ  NOT NULL,
  completed_at    TIMESTAMPTZ,
  PRIMARY KEY (merchant_id, idempotency_key)
);

CREATE INDEX idx_idempotency_keys_created_at
  ON idempotency_keys (created_at);
```

## Column meanings

- `merchant_id` — UUID of the authenticated merchant. Forms the first
  half of the composite key so each merchant has its own namespace.
- `idempotency_key` — VARCHAR(255), client-supplied via the
  `Idempotency-Key` HTTP header. 255 chars matches what Stripe and the
  IETF idempotency-key draft allow as a reasonable upper bound.
- `request_hash` — CHAR(64), hex-encoded SHA-256 of the canonicalized
  request body. Fixed-width because SHA-256 hex is always 64 chars;
  storing as `BYTEA` would save bytes but lose the ability to inspect
  values from `psql` without decoding.
- `status` — VARCHAR(20), one of `IN_PROGRESS` or `COMPLETED`. Stored
  as text rather than a Postgres enum because adding a third state
  (e.g. `STALE`, see the open question in ADR-0006) shouldn't require
  a schema migration.
- `response_status` — SMALLINT, the HTTP status code of the cached
  response.
- `response_body` — TEXT, the cached response body, stored as an
  opaque blob. The original V3 migration used JSONB; a later
  integration test caught Postgres normalizing key order and
  whitespace on round-trip, which would break the bit-for-bit
  replay contract from ADR-0006. V5 alters the column to TEXT so
  bytes go out the same way they came in.
- `resource_id` — UUID pointing to the created `authorizationId`,
  `captureId`, or `refundId`. Nullable while the request is
  `IN_PROGRESS`.
- `created_at` — TIMESTAMPTZ, set on insert. Drives the retention
  cleanup query.
- `completed_at` — TIMESTAMPTZ, set when the request finishes.

## Indexes

- Primary key on `(merchant_id, idempotency_key)` — gives lookup by
  composite key in one index probe.
- `idx_idempotency_keys_created_at` on `created_at` — supports the
  daily cleanup scan without a sequential scan.

## Cleanup query

Run daily via a scheduled job:

```sql
DELETE FROM idempotency_keys
WHERE created_at < now() - INTERVAL '24 hours';
```

The `idx_idempotency_keys_created_at` index keeps this cheap.

## Partitioning

Phase 1 doesn't partition this table. If the table grows large enough
that the daily cleanup scan becomes a problem, partitioning by
`created_at` (daily partitions, drop the oldest partition once per
day) is the next move. Not built yet.
