# Phase 1 write path

Backing decisions: ADR-0001, ADR-0004, ADR-0006

The write path serves the four mutating endpoints:
`POST /authorizations`, `POST /authorizations/{id}/captures`,
`POST /captures/{id}/refunds`, and `POST /authorizations/{id}/void`.
Same path for all four. Different aggregate method, different event.

## Sequence

```
client → REST controller → idempotency interceptor → application service
       → aggregate (domain logic) → repository → outbox writer → commit
```

A Mermaid sequence diagram will be added once the interceptor wiring
lands in the codebase. For now the textual walkthrough below is the
contract.

## Transaction body

A handler runs the following inside a single database transaction:

1. `INSERT INTO idempotency_keys (..., status='IN_PROGRESS')` with
   `ON CONFLICT DO NOTHING`. If the conflict fires, branch into the
   replay-or-409 path and return before touching the domain.
2. Run the aggregate logic. Load the relevant aggregate by ID, apply
   the command, validate invariants, persist the resulting aggregate
   row(s).
3. Write the corresponding domain event (`PaymentAuthorized`,
   `AuthorizationFailed`, `PaymentCaptured`, `CaptureFailed`,
   `PaymentRefunded`, `RefundFailed`, `AuthorizationVoided`,
   `AuthorizationExpired`) to the `outbox` table as a row carrying the
   full fat-event payload (ADR-0004).
4. Update the idempotency record to `COMPLETED`, setting
   `response_status`, `response_body`, `resource_id`, `completed_at`.
5. Commit.

All five steps share one transaction. If anything throws, the whole
thing rolls back — including the idempotency row, which means a future
retry sees no record and proceeds normally. This is intentional. The
idempotency record is only committed alongside a successful (or
deliberately-recorded-as-failed) domain action.

## Failure scenarios

- **Step 1 conflict, prior request `COMPLETED`** — the interceptor
  compares the new request's hash to the stored `request_hash`. Match
  → replay the cached `response_status` / `response_body`. Mismatch →
  `409 Conflict` with `idempotency_key_reuse_with_different_parameters`.
- **Step 1 conflict, prior request `IN_PROGRESS`** — return
  `409 Conflict` with `idempotency_request_in_progress`. The client
  retries after a short backoff.
- **Step 2 throws (invariant violation)** — the application service
  catches the domain exception, the transaction rolls back, the
  controller returns a 4xx response with a domain-specific error code.
  The idempotency row is gone (rolled back), so a corrected retry
  starts fresh.
- **Step 2 throws (infrastructure)** — database connection lost, query
  timeout, etc. Same rollback path; the client retries.
- **Step 3 throws** — outbox insert failure (constraint violation,
  serialization error). Same rollback. Unlikely in practice because
  the outbox table has minimal constraints, but the rollback is what
  protects us if it does happen.
- **Commit fails** — Postgres rolled back; client retries. The
  at-least-once guarantee on Kafka delivery picks up from the Debezium
  connector once the next attempt commits.

Debezium picks up committed outbox rows asynchronously after the
transaction completes. The write-path transaction does not wait for
Kafka delivery; that's what makes the outbox pattern's at-least-once
guarantee work without coupling the API latency to broker
availability.
