package dev.dogukankat.reconcile.payment.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Local-Postgres integration test, same pattern as the other ITs.
 * Wipes outbox between cases; nothing else in the test suite reads
 * from it yet so cross-test contamination is purely local.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboxRepositoryIT {

    @Autowired
    OutboxRepository repository;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void wipeOutbox() {
        jdbc.sql("DELETE FROM outbox").update();
    }

    @Test
    void appendAndReadBackRoundTrip() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEntry entry = new OutboxEntry(
                UUID.randomUUID(),
                "authorization",
                aggregateId,
                "PaymentAuthorized",
                // Opaque bytes since Phase 3 — the repository is
                // format-agnostic, so a non-UTF-8 byte sequence
                // (here a stand-in for Confluent-framed Avro:
                // magic byte 0x00 + body) round-trips intact.
                new byte[] {0x00, 0x00, 0x00, 0x01, 0x42},
                Instant.parse("2026-05-12T10:00:00Z"),
                "corr-abc");

        repository.append(entry);

        List<OutboxEntry> rows = repository.findByAggregateId(aggregateId);
        assertThat(rows).hasSize(1);
        OutboxEntry roundTripped = rows.get(0);
        assertThat(roundTripped.id()).isEqualTo(entry.id());
        assertThat(roundTripped.aggregateType()).isEqualTo("authorization");
        assertThat(roundTripped.eventType()).isEqualTo("PaymentAuthorized");
        assertThat(roundTripped.payload())
                .containsExactly(0x00, 0x00, 0x00, 0x01, 0x42);
        assertThat(roundTripped.occurredAt()).isEqualTo(entry.occurredAt());
        assertThat(roundTripped.correlationId()).isEqualTo("corr-abc");
    }

    @Test
    void appendAndReadBackPersistsNullCorrelationId() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEntry entry = new OutboxEntry(
                UUID.randomUUID(),
                "authorization",
                aggregateId,
                "AuthorizationExpired",
                "{}".getBytes(StandardCharsets.UTF_8),
                Instant.parse("2026-05-12T10:00:00Z"),
                null);

        repository.append(entry);

        List<OutboxEntry> rows = repository.findByAggregateId(aggregateId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).correlationId()).isNull();
    }

    @Test
    void multipleEventsForSameAggregateOrderByOccurredAt() {
        UUID aggregateId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-05-12T10:00:00Z");
        Instant t1 = t0.plusSeconds(60);
        Instant t2 = t0.plusSeconds(120);

        repository.append(entry(aggregateId, "PaymentCaptured", t2));
        repository.append(entry(aggregateId, "PaymentAuthorized", t0));
        repository.append(entry(aggregateId, "PaymentCaptured", t1));

        List<OutboxEntry> rows = repository.findByAggregateId(aggregateId);
        assertThat(rows)
                .extracting(OutboxEntry::occurredAt)
                .containsExactly(t0, t1, t2);
    }

    @Test
    void differentAggregatesAreIsolated() {
        UUID aggA = UUID.randomUUID();
        UUID aggB = UUID.randomUUID();
        Instant now = Instant.parse("2026-05-12T10:00:00Z");
        repository.append(entry(aggA, "PaymentAuthorized", now));
        repository.append(entry(aggB, "PaymentAuthorized", now));

        assertThat(repository.findByAggregateId(aggA)).hasSize(1);
        assertThat(repository.findByAggregateId(aggB)).hasSize(1);
    }

    private static OutboxEntry entry(UUID aggregateId, String type, Instant occurredAt) {
        return new OutboxEntry(
                UUID.randomUUID(),
                "authorization",
                aggregateId,
                type,
                "{}".getBytes(StandardCharsets.UTF_8),
                occurredAt,
                null);
    }
}
