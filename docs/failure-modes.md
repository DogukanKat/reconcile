# Failure modes

Six things that go wrong in production. Each entry is what the
failure looks like to an operator, what the system does
automatically, and what the operator should do manually. Nothing
in this file is theoretical — every mode is one a payments
platform actually hits.

The retry / DLQ chain from Phase 2 (ADR-0008) catches modes 1–4.
Modes 5 and 6 are about the write boundary; the idempotency
contract from ADR-0006 catches mode 5; mode 6 is a recovery story
for the outbox-to-Kafka bridge.

---

## 1. Transient downstream 5xx

A downstream service (payment processor, notification provider,
internal API) returns a 503 or times out for a window of seconds
to minutes. The listener can't complete its work and throws
`RetryableConsumerException`.

**Symptoms:** Lag on the source partition. Records reappearing on
`reconcile.authorization.v1-retry-0`, then `-retry-1`, then
`-retry-2`. Spring Kafka logs at INFO with the backoff.

**System response:** Each failed attempt is forwarded with
exponential backoff (1s, 3s, 9s). If the downstream recovers
within those three attempts, the message processes on the next
retry and never reaches the DLT.

**Operator action:** None usually. If you're paged, check the
downstream's status page. If the budget is going to be exhausted
(more than ~13s of downstream outage) and the rate of
`reconcile_consumer_retry_total` is climbing, consider whether
extending the budget is reasonable for this incident. Don't
extend it without a deliberate ADR amendment; ad-hoc tuning is
how retry policies become incoherent.

## 2. Persistent downstream failure → DLT

The downstream stays broken longer than the retry budget. Records
that hit the chain during the outage exhaust their retries and
land on `reconcile.authorization.v1-dlt`.

**Symptoms:** `consumer_dlt_received` log line with
`exception=RetryableConsumerException`. The
`reconcile_consumer_dlq_total` counter climbs with the same
exception class.

**System response:** The bad record is off the live partition.
The stream keeps moving for unrelated records. The DLT preserves
the original payload, the original headers (including
`correlationId`), and the truncated exception metadata for
inspection.

**Operator action:** Once the downstream is back up:

```
# 1. Confirm the downstream is healthy.
# 2. Pull the DLT records to inspect.
docker exec reconcile-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic reconcile.authorization.v1-dlt \
  --from-beginning \
  --property print.headers=true

# 3. Replay: re-publish each record's value to the source topic,
#    re-using the original `correlationId` so logs stitch back together.
#    There is no automated replay tooling in Phase 2; the manual
#    `kafka-console-producer` step is documented enough for an
#    operator to follow under stress.
```

## 3. Malformed payload → immediate DLT

A producer publishes a record whose payload doesn't parse, or
whose headers don't match the listener's contract (missing
`eventType` or `id`). The listener throws
`NonRetryableConsumerException` on the first attempt.

**Symptoms:** `consumer_dlt_received` with
`exception=NonRetryableConsumerException`, message
`"missing required header: eventType"` (or similar). The
`reconcile_consumer_dlq_total` counter shows a single tick per
bad record.

**System response:** Skip the retry chain entirely. The record
goes straight to `…-dlt` on the first failure. The retry
infrastructure is bypassed because the
`ConsumerErrorClassifier` (Feature 02) marks the exception
non-retryable.

**Operator action:** This is a producer bug or a schema drift, not
a downstream availability issue. Pull the DLT record. Inspect the
headers and payload. Fix the producer. Decide whether to replay
the historical bad records once the producer is fixed — usually
no, because a malformed record represents an event that was never
real.

## 4. Slow downstream causes consumer lag

The downstream is up but slow. Each call takes hundreds of
milliseconds instead of the usual tens. No exceptions are thrown.

**Symptoms:** Consumer lag on `reconcile.authorization.v1`
climbs. No retry counters move. No DLT records appear. The
processing path is healthy but throughput is below the publish
rate.

**System response:** None — there's no error to react to. The
retry chain isn't involved.

**Operator action:** Scale consumers horizontally
(`spring.kafka.listener.concurrency`) up to the partition count
of the source topic. Past the partition count, scaling stops
helping; that's when partition count itself needs to grow, which
is a planning conversation, not an incident response. The DLT
isn't involved here; this mode is in the doc because operators
tend to look here first when they see lag.

## 5. Idempotency key collision with different parameters

A client retries a request with the same `Idempotency-Key` but a
different request body. The hash on the stored idempotency row
doesn't match the new hash.

**Symptoms:** `IdempotencyHashMismatch` outcome on the
`reconcile_idempotency_outcome_total{outcome="hash_mismatch"}`
counter. HTTP 409 returned to the client with body
`{"error":"idempotency_key_reuse_with_different_parameters"}`.

**System response:** Reject the request. No aggregate write, no
outbox event, no consumer impact. The client sees the 409 and
knows they have a bug.

**Operator action:** None. This is a client-side bug; the loud
409 is the feature, not the failure. If a single client is
producing a sustained rate of these, that's a contact-the-customer
conversation, not a system response.

## 6. Outbox row written but Debezium is down

The aggregate transaction commits cleanly: idempotency row,
authorization row, outbox row, all in one Postgres transaction.
The Debezium connector is down, hung, or behind on its WAL slot.
The outbox row exists; the Kafka event hasn't been published yet.

**Symptoms:** Outbox table grows. `connect-status` shows the
connector in FAILED, PAUSED, or with growing replication lag.
Downstream consumers (e.g. notification-service) stop seeing new
events.

**System response:** At-least-once delivery still holds. Postgres
preserves the outbox row durably; the WAL position of the
connector's replication slot is persistent. When Debezium comes
back, it resumes from the slot's last LSN and catches up.

**Operator action:**

```
# 1. Confirm connector status.
curl -s http://localhost:8083/connectors/payment-outbox/status | jq

# 2. If FAILED, inspect the task error:
curl -s http://localhost:8083/connectors/payment-outbox/status \
  | jq '.tasks[0].trace'

# 3. If restarting fixes it:
curl -X POST http://localhost:8083/connectors/payment-outbox/restart

# 4. Watch the replication slot lag come down:
docker exec reconcile-postgres psql -U reconcile -d reconcile -c \
  "SELECT slot_name, active, pg_wal_lsn_diff(pg_current_wal_lsn(),
   confirmed_flush_lsn) AS lag_bytes FROM pg_replication_slots
   WHERE slot_name = 'reconcile_outbox_slot';"
```

The danger window for this mode is the time the connector is down
multiplied by the publish rate. As long as the WAL slot stays alive
(it does, because Debezium's slot is durable), nothing is lost.
The slot itself going away — by manual drop or by long-term
connector deletion — is a different, worse failure that's out of
scope for Phase 2's automation.

---

## What this doc deliberately doesn't cover

- **Network partition between payment-service and Postgres.**
  Spring + Hikari handle this with connection-pool retries; the
  outbox transaction either commits or doesn't. There's no
  Phase 2 design choice to document.
- **Kafka broker outage.** Phase 2 is single-broker compose; the
  failure mode is "service is down until broker is back". A
  real cluster story belongs in Phase 5.
- **Schema evolution breaking consumers.** That's Phase 3's
  problem, with Avro and Schema Registry. The current JSON wire
  format has no compatibility enforcement.
- **Replay tooling.** Manual `kafka-console-producer` is good
  enough for Phase 2; an actual replay UI or CLI is a Phase 3+
  conversation.
