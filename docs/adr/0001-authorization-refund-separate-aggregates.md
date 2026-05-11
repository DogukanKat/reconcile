# ADR-0001: Authorization and Refund as separate aggregates

- Status: Accepted
- Date: 2026-05-11
- Phase: 1 (Foundations)

## Context

Reconcile's domain has three entities that obviously belong together but
don't obviously belong inside the same transactional boundary:
authorizations, captures, and refunds. Before writing a single line of
domain code I had to decide where the aggregate seams go, because that
decision shapes the outbox payload, the transaction width, the
concurrency model, and the read-model design downstream.

I picked the Stripe/ISO-8583 style for the state machine in a separate
discussion (authorize and capture as distinct lifecycles, partial
captures allowed, refunds against captures). That decision narrowed the
question to "how many of these are aggregates, and what does each one
own?"

## Options considered

### (A) One aggregate: `Payment` owns everything

The `Payment` root carries the authorization, every capture, and every
refund. Single transaction, single optimistic-lock version, single
source of truth.

Pros: Invariants across the three are trivially enforced. There's no
cross-aggregate read for "is this fully refunded?".

Cons: The aggregate never dies. A merchant opens a dispute six months
after capture and the system has to load six months of history into a
single write transaction. Long-lived aggregates become concurrency hot
spots, and they grow until you regret them. In real payment systems
refunds arrive hours or days after settlement and follow their own
failure modes; bolting them onto the authorization lifecycle pretends
they're the same shape of thing, and they aren't.

### (B) Two aggregates: `Authorization` (owns captures) + `Refund` (references a capture)

Authorization is the root for the auth/capture lifecycle. Captures
live inside it because the core invariant
`sum(captures.SUCCEEDED) ≤ authorizedAmount` is local to that aggregate
and should stay local. Refunds are a separate aggregate keyed by
`captureId` because they have an independent lifecycle: they can arrive
long after the authorization has reached a terminal state, they have
their own failure modes (settlement adjustment, chargeback, network
reversal), and treating them as children of authorization would keep
that aggregate alive forever.

Pros: Each aggregate has a finite lifetime. Authorization is done once
it's `VOIDED` / `EXPIRED` / `AUTH_FAILED` or once captures are settled.
Refund's lifecycle is bounded to itself.

Cons: "Is this capture fully refunded?" is now a cross-aggregate query.
But that's a read concern, and the read model (Phase 4, Kafka Streams
projection) is what's going to answer it anyway.

### (C) Three aggregates: `Authorization`, `Capture`, `Refund` all separate

Maximum modularity. Each aggregate is small. But the invariant
`sum(captures.SUCCEEDED) ≤ authorizedAmount` now lives across two
aggregates, which means I have to enforce it with eventual consistency
and probably some kind of reservation/compensation pattern. That's
expensive coordination for a phase-1 service that doesn't need it.

## Decision

I went with **(B): Authorization + Refund**.

Authorization persists only its lifecycle states;
`PARTIALLY_CAPTURED` / `FULLY_CAPTURED` are derived at read time. The
reasoning is in ADR-0002.

Aggregate invariants are documented in docs/domain-model.md.

## Consequences

What this makes easier:

- The authorization aggregate has a finite lifetime. Once it reaches a
  terminal state or all captures settle, no further writes mutate it.
- Refund logic doesn't pollute the authorization aggregate. When I add
  chargeback handling later (it isn't in phase 1's scope), it lives on
  the refund side without dragging authorization concerns in.
- The capture-sum invariant is enforced inside a single transaction. No
  reservation/compensation choreography.

What this makes harder:

- Any query that wants "fully refunded" across a capture has to read
  from two aggregates. Phase 1 does this with a `JOIN`; phase 4 replaces
  it with the projection. I'm aware this is a read-path workaround until
  the projection is in place.

What I gave up:

- The cross-aggregate refund invariant (refund total ≤ capture amount)
  is enforced inside the refund aggregate using the capture amount it
  was initialized with. If somehow a capture's amount could mutate after
  the fact, this breaks. Captures are immutable post-`SUCCEEDED` in this
  model, so the invariant holds, but the assumption is load-bearing and
  I want it written down somewhere visible. That's here.

## Revisit when

Revisit this if refund volume drops near zero (the separation pays
less), or if cross-aggregate invariants start requiring saga-style
coordination (the separation costs more than it saves).

## Related decisions

- ADR-0002: Derived state for authorization status
- ADR-0004: Fat event payloads carry aggregate state across the outbox

## References

- Vaughn Vernon, *Implementing Domain-Driven Design*, chapter on
  aggregate design rules (small aggregates, reference by ID, eventual
  consistency across aggregates).
- Stripe API reference for the authorize/capture/refund model
  (https://stripe.com/docs/payments/payment-intents/verifying-status).
- The `sum(captures) ≤ authorized` invariant matches ISO-8583's
  partial-capture semantics; useful sanity check that the model isn't
  inventing a domain.
