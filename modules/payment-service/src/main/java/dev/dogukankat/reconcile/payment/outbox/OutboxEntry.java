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
 *
 * {@code payload} is opaque bytes as of Phase 3 (Option C): either
 * Confluent-framed Avro (PaymentAuthorized) or JSON bytes (the
 * not-yet-modelled event types). The outbox row is no longer
 * human-readable JSONB — that debuggability cost is named in
 * ADR-0009. {@code type}/{@code aggregateType}/{@code occurredAt}
 * stay readable columns so the row is still greppable even when the
 * body isn't.
 */
public record OutboxEntry(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        byte[] payload,
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
