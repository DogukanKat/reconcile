# shared-events

Avro schemas for cross-service events, and the generated record
classes the producer and consumer compile against.

`src/main/avro/` holds the `.avsc` files — the only source of truth.
The Gradle Avro plugin generates Java `SpecificRecord` classes under
`build/` (git-ignored) ahead of `compileJava`, so the jar this module
publishes carries the generated types on its API.

Phase 3 models `PaymentAuthorized` as the value schema for the
`reconcile.authorization.v1` topic. The other event types the Phase 1
outbox routes to that topic are not modelled here yet — that's a
deliberate "prove the pattern once" scope, recorded in ADR-0009. The
schema uses the `decimal` logical type for money (never float, per
ADR-0004) and `timestamp-micros` for the domain timestamp (matching
the microsecond resolution Postgres stores).

Nothing here puts bytes on the wire. The producer migration to Avro
is Phase 3 Feature 03; consumer deserialization is Feature 04.
