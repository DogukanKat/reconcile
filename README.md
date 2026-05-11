# Reconcile

Reconcile is a single-region payment service that takes authorize,
capture, refund, and void requests, drives them through a state
machine in Postgres, and publishes domain events to Kafka through the
transactional outbox pattern. I'm building it to settle the practical
limits, for my own clarity, of at-least-once delivery and idempotent
consumers in a payments domain — and to have something specific to
point at the next time someone says "exactly once" in a design
meeting. The interesting work isn't the happy path. It's what
the system does when Debezium falls behind, when a consumer fails
halfway through processing, and when a client retries the same
request after a connection drop.

## Status

Phase 1, foundations. No code yet — the work right now is design.
Six ADRs in `docs/adr/` cover the Phase 1 domain model and event
topology. Once those settle, payment-service gets a skeleton (Spring
Boot, Postgres, a single endpoint), then the outbox table and the
Debezium wiring, then notification-service as a first real consumer.

Later phases add retries and DLQ, schema evolution under Avro, a
Kafka Streams read-model projection, and the full operational
surface — metrics, tracing, load tests, documented failure modes.
The repo is shareable as of Phase 1; the ADRs alone signal the shape
of the work.

## How to read this repo

`docs/adr/` holds the architectural decisions, one per file. The
short version:

- ADR-0001 — why Authorization and Refund are separate aggregates
- ADR-0002 — why authorization status is derived, not persisted
- ADR-0003 — why commands and domain events are separate concepts
- ADR-0004 — why event payloads carry full state (fat events)
- ADR-0005 — why per-aggregate topics partitioned by authorizationId
- ADR-0006 — the idempotency contract: source, scope, storage,
  retention

`docs/domain-model.md`, `docs/storage/`, and `docs/architecture/` are
the non-decision references — schemas, write-path walkthroughs, state
diagrams. They link back to the ADRs that justify them.

## What this isn't

It isn't a real payment processor. There's no PCI scope, no card
networks, and no card numbers ever touch the system. It doesn't claim
end-to-end exactly-once delivery; the system itself is part of the
argument for why that claim doesn't survive scrutiny. It isn't
multi-region. It isn't a framework. It's a worked example with one
domain, built carefully enough that the decisions aren't accidents.

## Tech stack

Java 21 with virtual threads, records, and sealed types. Spring Boot
3.x. Gradle with the Kotlin DSL. Postgres holds state and the outbox
table; Debezium bridges it to Kafka. Phase 3 brings Avro and the
Confluent Schema Registry — JSON until then, deliberately, so the
schema-evolution exercises later have something to evolve from. Kafka
Streams handles the Phase 4 read-model projection. Testcontainers for
integration tests that touch real Postgres and Kafka. ArchUnit for
architectural rules. Micrometer, Prometheus, Grafana, OpenTelemetry
for the operational surface in Phase 5. Docker Compose to bring it
all up locally.

No Lombok. The language has caught up.
