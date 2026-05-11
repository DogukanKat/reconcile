# ADR-0005: Per-aggregate topics partitioned by authorizationId

- Status: Accepted
- Date: 2026-05-11
- Phase: 1 (Foundations)

Fat event payloads from ADR-0004 fit within Kafka's default message
size limits, which makes per-topic partitioning viable without payload
splitting.

## Context

Domain events leave the service through Kafka. The Kafka layout has
two choices that compose: how many topics, and what partition key.
Two questions, not one. Topic count shapes how schemas evolve and
which consumers see what. Partitioning shapes ordering and
parallelism. The mistake would be to answer them as if they were the
same question.

## Options considered

- **(A) Per-aggregate topics**: `reconcile.authorizations.v1`,
  `reconcile.captures.v1`, `reconcile.refunds.v1`. Each event type
  lives in its aggregate's topic.
- **(B) Single topic with Avro union**: `reconcile.payment-events.v1`
  carries every event type. Pulls in cross-aggregate ordering "for
  free" on a single partition.

## Decision

(A), with `authorizationId` as the partition key on all three topics.

## Reasoning

The cross-aggregate-ordering pitch of (B) is mostly a trap. Topic-wide
ordering only works on a single partition, which defeats horizontal
consumer scaling. The ordering anyone actually needs is
per-authorization ("voided before captured" must never be observed),
and that's preserved by using `authorizationId` as the partition key
in (A). Refunds get the same partition key so that all events touching
a given authorization — its own state changes, its captures, and its
refunds — land on the same partition and are delivered to the same
consumer instance in order.

Per-aggregate topics also make schema evolution easier in phase 3.
When I add a field to `PaymentCaptured`, the change is confined to the
captures topic's schema, and only consumers of that topic need to be
considered. With a single union schema, every change touches every
consumer.

Retention can be set per topic. Refunds may want longer retention than
captures (dispute windows can be 120+ days), and per-topic retention
makes that a config change rather than a re-partitioning project.

## Wire format across phases

Phase 1 uses JSON over Kafka. Schema Registry and Avro come in phase
3, where the schema-evolution scenarios get exercised on purpose. The
outbox payload is `JSONB` in Postgres for phase 1; in phase 3 it
becomes a binary Avro payload with the schema ID prefix that the
Confluent serializer expects. Notes-from-the-build will track the
migration.

## Consequences

What this makes easier:

- Schema evolution is per-topic, which keeps the phase-3 experiments
  scoped.
- Per-authorization ordering is guaranteed by partitioning, not by
  consumer-side coordination.

What this makes harder:

- A consumer that wants a view across all aggregates has to subscribe
  to three topics. Acceptable; consumers that wide are rare, and the
  phase-4 projection consolidates them anyway.

## Related decisions

- ADR-0003: Commands and domain events as separate concepts
- ADR-0004: Event-carried state transfer (fat events)
