# notification-service

The first downstream consumer of the authorization event stream.
Subscribes to `reconcile.authorization.v1` and logs every event for
now — real side-effects (email, push, webhook) come once their shape
is more than a guess.

## Running locally

```bash
# Stack up (Postgres, Kafka, Debezium) and connector registered:
make up
make register-connector

# Payment-service in another terminal (port 8080):
./gradlew :modules:payment-service:bootRun

# Notification-service in this terminal:
./gradlew :modules:notification-service:bootRun

# Hit the authorize endpoint; the event appears in notification-service's log.
curl -X POST http://localhost:8080/authorizations \
  -H "Idempotency-Key: notif-test" \
  -H "Content-Type: application/json" \
  -d '{"merchantId":"00000000-0000-0000-0000-000000000001",
       "amount":"10.00","currency":"USD",
       "expiresAt":"2026-05-19T10:00:00Z"}'
```

## What this service deliberately does not do (yet)

- No idempotent processing. The Debezium SMT puts the outbox row id
  in the `id` header; a real consumer would dedupe on it. This one
  is log-only so duplicates from at-least-once delivery are
  tolerable.
- No DLQ or retry topic — Phase 2.
- No metrics. Phase 5.
