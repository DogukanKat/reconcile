# ADR-0003: Commands and domain events as separate concepts

- Status: Accepted
- Date: 2026-05-11
- Phase: 1 (Foundations)

## Context

The README draft and CLAUDE.md both said the service "ingests payment
events (authorize, capture, refund, void), processes them through a
state machine, and publishes domain events." That phrasing collapses
two very different things into one word. Things coming in from clients
are imperative requests; things going out to consumers are immutable
facts about what already happened. Confusing the two leads to event
payloads that look like half-commands ("PleaseChargeThisCard"), which
then makes downstream consumers harder to reason about and schema
evolution painful.

## Options considered

- **(A) Single "event" concept used for both inbound and outbound** —
  match the README's original phrasing, keep one mental model.
- **(B) Explicit split: commands inbound, domain events outbound** —
  the standard CQRS-flavored convention.

## Decision

(B). Commands are imperative, present-tense, addressed to the service
(`AuthorizePayment`, `CapturePayment`, `RefundPayment`,
`VoidAuthorization`). They arrive as REST DTOs in phase 1, validated at
the boundary, and translated into aggregate method calls inside the
domain. They are not persisted as commands and never leave the service.

Domain events are past-tense, immutable facts (`PaymentAuthorized`,
`AuthorizationFailed`, `PaymentCaptured`, `CaptureFailed`,
`PaymentRefunded`, `RefundFailed`, `AuthorizationVoided`,
`AuthorizationExpired`). Each corresponds to a real state transition
inside an aggregate; each gets written to the outbox in the same
transaction as the aggregate change, then carried to Kafka by Debezium
CDC.

## Reasoning

If commands and events share a type, the API ends up shaped like an
event log ("submit an event called CapturePayment") and that conflates
intent with outcome. A command can be rejected; an event has already
happened and downstream consumers can rely on it. Mixing them turns
"did this succeed?" into a question consumers have to keep asking.

## Consequences

- Validation lives at the API boundary; the domain only sees calls
  that have already passed structural checks.
- Consumers can treat outbox-fed Kafka messages as ground truth, not
  pending requests.
- The command DTO schema and the domain event schema evolve
  independently. Adding a field to `CapturePayment` doesn't touch
  `PaymentCaptured` and vice versa.

## Related decisions

- ADR-0004: Event-carried state transfer (fat events)
- ADR-0005: Per-aggregate topics partitioned by authorizationId
