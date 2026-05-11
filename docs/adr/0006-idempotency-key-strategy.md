# ADR-0006: Idempotency key strategy

- Status: Accepted
- Date: 2026-05-11
- Phase: 1 (Foundations)

## Context

Phase 2 adds retry topics, dead-letter routing, and consumer retries.
None of that is safe without idempotency on the write side. A network
glitch retried twice without dedup is a double-charge. CLAUDE.md
called out "idempotency keys with a defined retention and collision
strategy" as a required property of the system, and I wanted that
decision pinned down in phase 1 — before retry logic existed — so the
storage shape and the API contract were already in place when retries
arrived.

Idempotency at the write boundary is six small decisions that have to
agree with each other: where the key comes from, what its namespace
is, where it's stored, how collisions are detected, what happens when
two requests with the same key arrive concurrently, and how long the
record lives. Getting any one of these wrong while the others look
correct is how you build a system that silently misroutes retries.

## Decisions and the reasoning behind each

### Key source: client-supplied HTTP header `Idempotency-Key`

Stripe's model. A server-generated key gives the client no way to
retry the same logical request, which is the whole point of the
mechanism. I considered no other option seriously.

The header is required on all mutating endpoints (`POST` for
authorize, capture, refund, void); missing it returns
`400 Bad Request`. I'd rather be strict than have clients
"accidentally" make non-idempotent calls.

### Scope: `(merchant_id, idempotency_key)`

Key uniqueness is per-merchant, not global. Two merchants can reuse
the same string without colliding. The major payment processors do
this; a global namespace would push the "don't collide with anyone"
burden onto every merchant for no benefit.

The merchant ID comes from the authenticated principal, not from a
request field, so a client can't spoof another merchant's namespace.

### Storage: separate `idempotency_keys` table

A separate `idempotency_keys` table keyed by `(merchant_id,
idempotency_key)`, with a `request_hash` for collision detection,
`status` for tracking, and cached response fields for replay. Full
schema in docs/storage/idempotency-keys-schema.md.

I considered putting the key on the `authorizations` table with a
unique index. Two problems: it scatters the mechanism across the
domain (one column per endpoint), and it leaves nowhere to cache the
full response. A separate table keeps the mechanism in one place and
stays out of the domain.

### Collision detection: SHA-256 of canonical-JSON request body

When a request arrives with a known `(merchant_id, idempotency_key)`,
the service compares the new request's body hash to the stored one.
Match → replay. Mismatch → `409 Conflict` with error code
`idempotency_key_reuse_with_different_parameters`.

Canonicalization: keys sorted alphabetically, no whitespace,
deterministic number formatting (decimals as strings to avoid
float/int ambiguity), timestamps and request-id fields excluded.
The excluded fields are listed explicitly per endpoint, not inferred
— implicit exclusion rules drift.

The mismatch case isn't a paranoid feature. It catches real client
bugs (e.g. a stale retry-loop variable reusing a key with different
parameters). Silent success would be a double-charge of a different
amount, worse than a 409.

### Concurrent in-flight requests: fail fast with 409

If two requests with the same `(merchant_id, idempotency_key)` arrive
nearly simultaneously, the first wins the `INSERT ... ON CONFLICT DO
NOTHING` and proceeds. The second sees the existing `IN_PROGRESS` row
and returns `409 Conflict` with `idempotency_request_in_progress`.

I considered "wait and return" (second request blocks, then replays).
Nicer client-side but brings real failure modes — connection pool
exhaustion, timeout semantics, wait-and-notify on Postgres rows. B2B
API; clients handle their own retries. Fail-fast is simpler and
harder to get wrong.

### Retention: 24 hours, deleted by a daily job

After 24 hours, the row is deleted. A subsequent request with the
same key creates a new resource — no replay, the record is gone. A
deliberate cutoff.

24h is Stripe's window. Most network retry budgets fit inside it
comfortably; longer retention just costs storage. I considered 7 days
— defensible if the SLA required it — but couldn't justify a number
bigger than the longest reasonable retry interval.

## Composition with the outbox

The idempotency record, the aggregate write, and the outbox event are
written in one database transaction. The full write-path sequence is
in docs/architecture/write-path.md.

## Consequences

What this makes easier:

- Phase 2's retry topics become safe to ship.
- Real client bugs (reusing a key with a different body) surface as
  loud 409s instead of silent successes.
- Replay is bit-for-bit; clients comparing responses across retries
  get identical bytes back.

What this makes harder:

- Every mutating endpoint must go through the idempotency middleware.
  ArchUnit rule on `@PostMapping` controllers.
- Canonical JSON serialization must stay deterministic across Jackson
  versions. Serializer config pinned in one place, hash stability
  asserted in a property-based test.
- The 24-hour window is global. A per-merchant override isn't built.
  Documented here so I don't forget I deferred it.

## Open question I'm leaving for phase 2

Whether to add a separate "stale completed" status for records past
their retention window but not yet deleted by the cleanup job. The
race is real (cleanup runs daily, a request mid-window could see a
26-hour-old row) but the consequence is benign (replay an old
response). I'll watch for it in phase 2 load tests before adding
machinery for it.

## Related decisions

- ADR-0001: Authorization and Refund as separate aggregates (defines
  which aggregate the write transaction targets).
- ADR-0004: Event-carried state transfer (the cached response body
  caches what the consumer-facing event payload was based on).

## References

- Stripe's idempotency guide
  (https://stripe.com/docs/api/idempotent_requests). The model here is
  essentially theirs, with the storage shape spelled out.
- IETF draft on idempotency keys
  (https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/)
  — the header name and 409 semantics are aligned with the draft.
- Brandur Leach's "Implementing Stripe-like Idempotency Keys in
  Postgres" (https://brandur.org/idempotency-keys) covers the same
  shape with more attention to the transaction-vs-RPC boundary.
- Chris Richardson, *Microservices Patterns*, chapter 3 (outbox
  pattern). The transactional bundling of the idempotency row, the
  aggregate write, and the outbox row is the outbox pattern with
  idempotency layered on top.
- Debezium docs on the transactional outbox event router
  (https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html).
