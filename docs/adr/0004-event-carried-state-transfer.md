# ADR-0004: Event-carried state transfer (fat events)

- Status: Accepted
- Date: 2026-05-11
- Phase: 1 (Foundations)

This decision assumes the command/event split from ADR-0003.

## Context

Domain events get written to the outbox in the same transaction as the
aggregate change and are then carried to Kafka by Debezium CDC. The
open question is what those event payloads carry: enough state for the
consumer to act on its own. Or just IDs, with a callback to the
service for everything else.

## Options considered

- **(A) Fat events** — payload carries enough state for the consumer
  to act without calling back to the service. For `PaymentAuthorized`:
  `authorizationId, merchantId, amount, currency, authorizedAt,
  expiresAt, idempotencyKey`.
- **(B) Thin events** — payload carries only `{resourceId, occurredAt,
  type}`. Consumers call back to the service for details when they
  need them.

## Decision

(A).

## Reasoning

Thin events bring back the coupling the outbox was supposed to remove.
Every consumer that wants to act on `PaymentCaptured` would have to
make a synchronous HTTP call to payment-service to find out what was
captured, turning payment-service into a sync dependency of every
downstream system. That's the exact coupling event-driven architecture
is supposed to break.

There's a second reason specific to the CDC approach: Debezium reads
the outbox table and publishes whatever the row contains. If outbox
rows carry only IDs, the Kafka message is also just an ID, which means
the CDC step is doing no useful work beyond hand-rolled polling would
do. Fat outbox rows are what makes the outbox-to-Kafka bridge
meaningful.

The payload is small and contains no PCI data (no PAN, no CVV — this
service never sees those). `idempotencyKey` is included so consumers
can implement their own dedup on the read side, which they'll need when
at-least-once delivery causes a duplicate. `amount` is `BigDecimal` in
domain code, encoded as a string in JSON / decimal logical type in
Avro to avoid float precision issues.

## Consequences

What this makes easier:

- Consumers can be written without depending on payment-service's HTTP
  endpoints. Notification-service in phase 1 will consume
  `reconcile.authorizations.v1` and act purely on payload.

What this makes harder:

- Schema changes have to be backward-compatible (consumers will be
  reading old messages from retention). Phase 3 exercises this on
  purpose — adding a field is easy, removing one isn't.
- The outbox payload is bigger than a thin-event design. Storage and
  CDC bandwidth are higher. At phase-1 volumes this is irrelevant; if
  it ever stops being irrelevant the cost is solvable (compaction,
  outbox archival), but it's a real cost.

## References

- Sam Newman, *Building Microservices*, 2nd ed., chapter 4
  (event-carried state transfer).
- Confluent's "Event-Carried State Transfer" blog post
  (https://www.confluent.io/blog/event-driven-2-event-carried-state-transfer-pattern/).

## Related decisions

- ADR-0003: Commands and domain events as separate concepts
- ADR-0005: Per-aggregate topics partitioned by authorizationId
