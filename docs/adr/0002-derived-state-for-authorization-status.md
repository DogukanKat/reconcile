# ADR-0002: Derived state for authorization status

- Status: Accepted
- Date: 2026-05-11
- Phase: 1 (Foundations)

Context for this decision is in ADR-0001.

## Context

ADR-0001 settled on Authorization as an aggregate that owns its
captures. That leaves one open question: when there are captures
against an authorization, where does the "how much is captured so far"
state live? Persisted on the authorization row alongside `AUTHORIZED`
and the other lifecycle states, or computed from the captures
themselves at read time?

## Options considered

### (A) Persist `PARTIALLY_CAPTURED` / `FULLY_CAPTURED` as explicit states

The authorization row carries one of `INITIATED`, `AUTHORIZED`,
`PARTIALLY_CAPTURED`, `FULLY_CAPTURED`, `VOIDED`, `EXPIRED`,
`AUTH_FAILED`. Each capture write updates the authorization's state in
the same transaction.

### (B) Derive from `sum(captures.SUCCEEDED.amount)` at read time

The authorization row carries only `INITIATED`, `AUTHORIZED`, `VOIDED`,
`EXPIRED`, `AUTH_FAILED`. Whether the authorization is partially or
fully captured is computed from the capture rows when callers ask.

## Decision

(B).

Persisting `PARTIALLY_CAPTURED` duplicates information that the
captures table already tells the truth about, and duplicated state has
one job in production — drift. Either the authorization state and the
capture sum disagree (which one is right? nobody knows), or every
capture write has to update two rows in lockstep forever. I'd rather
pay a tiny read cost in phase 1 and let the phase-4 projection
materialize the rollup once it exists.

## Consequences

The API layer has to compute derived state on read. I'll do this once,
in a query-side helper, not scattered across controllers. If I find
myself computing it in three places I've done it wrong.

## Related decisions

- ADR-0001: Authorization and Refund as separate aggregates
