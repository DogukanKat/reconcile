# ADR-0008: Retry topic and DLQ strategy

- Status: Accepted
- Date: 2026-05-14
- Phase: 2 (Reliability)

## Context

Phase 2 had to answer two questions the Phase 1 happy path got to
duck: what does the consumer do when processing fails, and where
does the message go when it really can't be processed. The wrong
answer to either is how event-driven systems become slow-motion
incidents. A blocked partition will catch up to operations within
an hour. A retry loop on a poison pill will not.

The single decision underneath both questions is the same:
"retryable" and "non-retryable" failures look identical at the
moment they happen. Network blips and malformed-schema failures
both arrive as a thrown exception from inside a Kafka listener.
Treating them the same — by either retrying everything or
retrying nothing — destroys the system within a week of any real
traffic. So the design has to make the distinction explicit and
fail safely when the distinction is unclear.

## Options considered

- **(A) Block on retry in the listener thread.** Spring Kafka's
  `DefaultErrorHandler` with a `BackOffPolicy` does this without
  any topic infrastructure. Simple, but a stuck record blocks
  every other record on its partition for the duration of the
  backoff. At any meaningful throughput this becomes lag fast.
- **(B) Custom scheduler service.** A dedicated process polls a
  retry table or queue, re-publishes on backoff. Full control,
  full operational surface. Useful when the retry policy needs to
  be reasoned about as its own thing; overkill for a single
  domain.
- **(C) Spring Kafka retry-topic chain.** The
  `RetryTopicConfiguration` mechanism: each failed record is
  forwarded to `<topic>-retry-N` with a per-attempt delay, then
  on to `<topic>-dlt` after the budget is exhausted. The retry
  topics are real Kafka topics, so the consumer thread isn't
  blocked, and the retry state is visible in the broker like any
  other message.

## Decision

(C). With these knobs:

- **Backoff**: exponential, starting at 1 second, multiplier 3,
  capped at 5 minutes. Four total attempts (the initial + three
  retries → 1s, 3s, 9s).
- **Classification**: `RetryableConsumerException` and
  `SocketTimeoutException` in the cause chain are retryable.
  Everything else — including unknown `RuntimeException` — is
  treated as non-retryable.
- **Topic naming**: `<source>-retry-0`, `-retry-1`, `-retry-2`,
  `-dlt`. Index-based suffixing via
  `suffixTopicsWithIndexValues()`; the default is delay-based
  (`-retry-1000`) and unreadable when backoffs change.
- **Consumer group**: same as the source-topic listener. Spring
  Kafka derives `-retry-N` and `-dlt` group IDs from the main
  group so partition ownership stays consistent.

The conservative classifier default (unknown exception →
non-retryable) is the load-bearing part. Retry-by-default is the
textbook way to amplify one bad message into a saturated retry
topic, then a backlog on every other consumer, then a page at 3 AM.

## Reasoning

The retry-topic chain (C) trades a couple of derived topics on the
broker for not blocking the source partition. Option (A) is cleaner
to reason about until you watch a single 30-second downstream blip
push the consumer minutes behind the live event stream; the topic
chain absorbs that without dropping anything or delaying unrelated
records. Option (B) is what I'd build if retries needed to be
inspectable as their own domain (e.g., a customer-facing "your
payment is being retried" UI), which this service doesn't.

The classifier shape (`retryOn` whitelist + `traversingCauses`)
mirrors Feature 02's `ConsumerErrorClassifier` exactly. Same
list of exception types, same cause-chain traversal. Two
implementations of the same rule felt wrong; the unit tests for
the classifier are the executable spec, and the retry-topic
config refers to the same exception classes. If the classification
ever diverges, the IT in Feature 05 catches it.

The 24-hour question for DLT retention is not in this ADR
deliberately. The Kafka broker config holds that knob; the right
value depends on how long an on-call rotation might take to
notice and how long the legal/audit window is. 30 days is a
reasonable starting point. The DLT consumer (`@DltHandler` in
`AuthorizationEventListener`) is log-only; nothing automatically
reprocesses. Replay is a deliberate human action, documented in
`docs/failure-modes.md`.

## What this makes easier

- A retryable downstream failure (503, transient lock timeout)
  recovers without operator action. Throughput dips for the
  affected key, the rest of the stream keeps flowing.
- A poison message — malformed payload, schema drift, business
  invariant violation — is off the live partition before the next
  record is read. The DLT preserves the original payload plus
  failure metadata so it's diagnosable.
- The retry budget is observable on the broker. `kafka-console-consumer`
  on `<source>-retry-2` is the operator's view into "what's
  currently stuck and on its last attempt."

## What this makes harder

- The set of topics for a single logical stream is now five
  (source + 3 retry + DLT). A reader who's never seen the pattern
  has to find the connector between them, which lives in
  `KafkaRetryConfig`.
- Per-merchant override of the retry budget isn't supported. Adding
  it would require either a second retry chain (operational mess)
  or a custom `BackOff` keyed on record metadata. The latter is
  doable; deferred until a real reason shows up.
- Ordering across retries is best-effort. A record that fails and
  re-enters the retry topic later than a subsequent record on the
  same key can be processed out of original order. The aggregate
  state machine enforces the real invariant (you can't void what's
  already captured), so the consumer-side ordering relaxation is
  acceptable.

## When I'd reconsider Spring Kafka's mechanism

Spring Kafka's retry-topic config is fine for a single domain and
a single failure model. The day I'd swap it for a custom scheduler
or a stream processor:

- We need first-class retry observability for non-operators — a UI
  that says "your request is being retried, here's the budget
  remaining". The retry-topic chain doesn't expose that cleanly.
- The retry policy needs per-merchant or per-event-type
  configuration that doesn't fit "one backoff for everyone".
- Retry-state durability has to outlive Kafka retention. The retry
  topics inherit Kafka retention; if a retry needs to wait 14
  days, retention has to be longer than the longest attempt, and
  that's an awkward broker-level decision.

None of those are true today.

## Open questions

- **Per-merchant retention on the DLT.** A high-volume merchant
  with persistent malformed events would push retention pressure on
  the DLT broker-side. Could be addressed with a per-merchant DLT
  topic (`<source>-dlt-<merchantId>`) but the cardinality risk
  rules it out for now. Phase 5 load tests will say more.
- **Retry-counter metric.** Spring Kafka doesn't expose a clean
  hook for "this record was just retried". The Kafka header
  `kafka_attempt-count` is only on retry-topic records, and
  counting it there would double-count any record that visits
  multiple retry topics before hitting the DLT. Punting to Phase 3
  with a `ConsumerRecordRecoverer` wrap. DLT counter is in place
  in the meantime.

## Consequences for related decisions

- ADR-0005 (per-aggregate topics + partitioning by authorizationId)
  composes cleanly with this: each per-aggregate topic gets its
  own retry/DLT chain when a consumer is wired to it. So far only
  `reconcile.authorization.v1` has a consumer.
- ADR-0006 (idempotency key strategy) is the symmetric thing on
  the write side: idempotent producers protect against
  double-processing across retries. Without ADR-0006, this retry
  policy would be unsafe.

## References

- Confluent docs on retry topics
  (https://docs.confluent.io/platform/current/streams/concepts.html#retry)
  — the conceptual model.
- Spring Kafka reference, "Non-Blocking Retries"
  (https://docs.spring.io/spring-kafka/reference/retrytopic.html)
  — the implementation primitives, including the
  `RetryTopicConfigurationSupport` hook that took a few iterations
  to land correctly.
- Confluent's "Error Handling Patterns for Apache Kafka
  Applications" (https://www.confluent.io/blog/error-handling-patterns-in-kafka/)
  — particularly the "Dead Letter Topic with Backoff" pattern
  this design follows.
- ADR-0006 (idempotency key strategy) — the write-side guarantee
  that makes the retry-on-failure path safe.
