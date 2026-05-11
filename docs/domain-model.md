# Reconcile domain model

Backing decisions: ADR-0001, ADR-0002

## Authorization aggregate

Root entity for the authorize/capture lifecycle. Carries
`authorizationId`, `merchantId`, `authorizedAmount`, `currency`,
`expiresAt`, lifecycle `state`, and a list of `Capture` child entities.

### Authorization states

- `INITIATED` — request accepted, authorization not yet attempted
- `AUTHORIZED` — funds reserved at the network
- `VOIDED` — terminal, authorization cancelled before any capture
- `EXPIRED` — terminal, authorization aged out without capture
- `AUTH_FAILED` — terminal, network declined the authorization

`PARTIALLY_CAPTURED` and `FULLY_CAPTURED` are not persisted states;
they are derived at read time from `sum(captures.SUCCEEDED.amount)`
against `authorizedAmount`. See ADR-0002.

### Capture states (inside the Authorization aggregate)

- `PENDING` — capture submitted to network
- `SUCCEEDED` — funds captured
- `FAILED` — network refused the capture

### Authorization invariants

- `sum(captures.SUCCEEDED.amount) ≤ authorizedAmount`
- Transition to `VOIDED` requires
  `state == AUTHORIZED && captures.isEmpty()`
- Transition to `EXPIRED` requires
  `state == AUTHORIZED && now > expiresAt`
- Captures are immutable once they reach `SUCCEEDED`

These invariants will be guarded by domain unit tests and reinforced
by ArchUnit rules that prevent other layers from writing to aggregate
state directly.

## Refund aggregate

Separate aggregate, keyed by `refundId`, holding a `captureId`
reference, an `amount`, and a lifecycle `state`. Lives independently
of the authorization aggregate (see ADR-0001).

### Refund states

- `PENDING` — refund submitted to network
- `SUCCEEDED` — refund completed
- `FAILED` — network refused the refund

### Refund invariants

- Parent capture must be in `SUCCEEDED` state when the refund is
  initiated
- `sum(existing_refunds.SUCCEEDED.amount) + new.amount ≤ capture.amount`
- The capture amount the refund was initialized with is treated as
  immutable; this is the load-bearing assumption called out in
  ADR-0001.
