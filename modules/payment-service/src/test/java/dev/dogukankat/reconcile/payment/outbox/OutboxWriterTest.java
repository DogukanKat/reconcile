package dev.dogukankat.reconcile.payment.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import dev.dogukankat.reconcile.payment.authorization.AuthorizationId;
import dev.dogukankat.reconcile.payment.authorization.DomainEvent.PaymentAuthorized;
import dev.dogukankat.reconcile.payment.authorization.MerchantId;
import dev.dogukankat.reconcile.payment.authorization.Money;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxWriterTest {

    private CapturingRepository repository;
    private OutboxWriter writer;

    @BeforeEach
    void setUp() {
        repository = new CapturingRepository();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        writer = new OutboxWriter(repository, objectMapper);
    }

    @Test
    void publishWritesEntryWithDebeziumCompatibleFields() {
        AuthorizationId authId = AuthorizationId.generate();
        MerchantId merchantId = new MerchantId(UUID.randomUUID());
        Instant occurredAt = Instant.parse("2026-05-12T10:00:00Z");
        PaymentAuthorized event = new PaymentAuthorized(
                authId,
                merchantId,
                new Money(new BigDecimal("100.00"), "USD"),
                occurredAt);

        OutboxEntry entry = writer.publish(event);

        assertThat(entry.aggregateType()).isEqualTo("authorization");
        assertThat(entry.aggregateId()).isEqualTo(authId.value());
        assertThat(entry.eventType()).isEqualTo("PaymentAuthorized");
        assertThat(entry.occurredAt()).isEqualTo(occurredAt);
        assertThat(entry.payload())
                .contains("\"authorizationId\"")
                .contains(authId.value().toString())
                .contains("\"USD\"");
    }

    @Test
    void publishAppendsEntryToRepository() {
        PaymentAuthorized event = new PaymentAuthorized(
                AuthorizationId.generate(),
                new MerchantId(UUID.randomUUID()),
                new Money(new BigDecimal("50.00"), "EUR"),
                Instant.now());

        writer.publish(event);

        assertThat(repository.entries).hasSize(1);
    }

    /** Test double — keeps the unit test JdbcClient-free. */
    static class CapturingRepository extends OutboxRepository {
        final List<OutboxEntry> entries = new ArrayList<>();

        CapturingRepository() {
            super(null);
        }

        @Override
        public void append(OutboxEntry entry) {
            entries.add(entry);
        }
    }
}
