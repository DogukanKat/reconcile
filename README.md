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

Phase 2, reliability — six features stacked as PRs on top of the
Phase 1 happy path. Phase 1 (Foundations) shipped the domain model,
state machine, outbox-to-Kafka bridge via Debezium, and a first
consumer in notification-service. Phase 2 turns the happy path into
something that survives transient failures and bad inputs without
losing events or double-charging:

- **Idempotency operational hardening** — retention scheduler,
  ArchUnit guardrail on `@PostMapping` controllers, correlation ID
  propagation end-to-end through MDC, outbox row, Kafka header, and
  consumer logs.
- **Consumer error taxonomy** — sealed `RetryableConsumerException`
  / `NonRetryableConsumerException` with a classifier defaulting to
  non-retryable (retry-by-default amplifies poison pills).
- **Retry topic with exponential backoff** — Spring Kafka's
  `RetryTopicConfiguration`, 1s/3s/9s, four total attempts, scoped
  per topic.
- **Dead-letter routing** — `DeadLetterPublishingRecoverer` with a
  stack-trace-truncating header creator, `@DltHandler` for the
  structured log line that pages someone.
- **Poison-message integration test** — three scenarios end-to-end
  against the local Kafka brought up by `make up`.
- **Observability and docs** — Prometheus counters (DLT publishes,
  idempotency outcomes, retention deletions), ADR-0008 for the
  retry/DLQ strategy, `docs/failure-modes.md` for the six modes
  Phase 2 is designed to survive.

Later phases add schema evolution under Avro (Phase 3), a Kafka
Streams read-model projection (Phase 4), and the full operational
surface — distributed tracing, load tests, and dashboards-as-code
(Phase 5). The repo has been shareable since Phase 1; Phase 2
turns the demo into something operationally credible.

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
- ADR-0007 — JdbcClient over JPA for aggregate persistence
- ADR-0008 — retry topic and DLQ strategy: backoff, classification,
  topic naming, and the limits at which I'd reconsider Spring Kafka

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
