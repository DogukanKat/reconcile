package dev.dogukankat.reconcile.payment.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of the outbox table. Field names mirror the
 * Debezium-friendly column names; aggregateType / aggregateId /
 * eventType stay camelCase in Java because that's idiomatic, and the
 * repository maps to the database casing on the way in and out.
 *
 * {@code correlationId} is nullable on purpose: server-initiated
 * events (e.g. the expiry scheduler) run outside any HTTP request and
 * therefore have no correlation context.
 */
public record OutboxEntry(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String payload,
        Instant occurredAt,
        String correlationId) {

    public OutboxEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
