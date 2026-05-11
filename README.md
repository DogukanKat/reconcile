# Reconcile

Reconcile is a single-region payment service that takes authorize,
capture, refund, and void requests, drives them through a state
machine in Postgres, and publishes domain events to Kafka through the
transactional outbox pattern. I'm building it to settle, for myself,
the practical limits of at-least-once delivery and idempotent
consumers in a payments-shaped domain — and to have something
specific to point at the next time someone says "exactly once" in a
design meeting. The interesting work isn't the happy path. It's what
the system does when Debezium falls behind, when a consumer fails
halfway through processing, and when a client retries the same
request after a connection drop.

## Status

Phase 1, foundations. No code yet — the work right now is design.
Six ADRs in `docs/adr/` cover the Phase 1 domain model and event
topology. Once those settle, payment-service gets a skeleton (Spring
Boot, Postgres, a single endpoint), then the outbox table and the
Debezium wiring, then notification-service as a first real consumer.
Phase 2 brings retries and DLQ. Phase 3 puts Avro and Schema Registry
in the path and exercises schema evolution on purpose. Phase 4 is the
Kafka Streams read-model projection. Phase 5 is the operational
surface — metrics, tracing, load tests, documented failure modes.

The repo is shareable as of Phase 1; the ADRs alone signal the shape
of the work. Future phases harden it.

## How to read this repo

`docs/adr/` holds the architectural decisions, one per file, numbered
in order. Start with `0001` if you want the domain model. Jump to
`0006` if you want the idempotency contract.

`docs/domain-model.md`, `docs/storage/`, and `docs/architecture/` are
the non-decision references — schemas, write-path walkthroughs, state
diagrams as they get drawn. They link back to the ADRs that justify
them.

## What this isn't

It isn't a real payment processor. There's no PCI scope, no card
networks, and no card numbers ever touch the system. It doesn't claim
end-to-end exactly-once delivery; the system itself is part of the
argument for why that claim is usually marketing. It isn't
multi-region. It isn't a framework. It's a worked example with one
domain, built carefully enough that the decisions hold up to scrutiny.

## Tech stack

Java 21 with virtual threads, records, and sealed types. Spring Boot
3.x. Gradle with the Kotlin DSL. Postgres for state and the outbox
table. Debezium for the CDC bridge from Postgres to Kafka. Kafka with
Avro and the Confluent Schema Registry arriving in Phase 3 (JSON
until then). Kafka Streams for the read-model projection in Phase 4.
Testcontainers for integration tests against real infrastructure.
ArchUnit for architectural rules. Micrometer, Prometheus, Grafana,
and OpenTelemetry for the operational surface. Docker Compose to
bring the whole thing up locally.

No Lombok. The language has caught up.

## Running it

Nothing to run yet. `docker-compose up` lands later in Phase 1, once
payment-service has a domain to expose.
