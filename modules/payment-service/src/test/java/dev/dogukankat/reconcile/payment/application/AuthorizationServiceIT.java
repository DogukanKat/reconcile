package dev.dogukankat.reconcile.payment.application;

import dev.dogukankat.reconcile.payment.authorization.AuthorizationId;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationRepository;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus;
import dev.dogukankat.reconcile.payment.authorization.MerchantId;
import dev.dogukankat.reconcile.payment.authorization.Money;
import dev.dogukankat.reconcile.payment.outbox.OutboxEntry;
import dev.dogukankat.reconcile.payment.outbox.OutboxRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the authorize use case against local Postgres.
 * Asserts the three-row transaction: idempotency_keys flips to
 * COMPLETED, authorizations carries the AUTHORIZED row, outbox holds
 * the PaymentAuthorized event keyed on the same authorizationId.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuthorizationServiceIT {

    @Autowired AuthorizationService service;
    @Autowired AuthorizationRepository authorizations;
    @Autowired OutboxRepository outboxRepo;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void wipeTables() {
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM idempotency_keys").update();
        jdbc.sql("DELETE FROM authorizations").update();
    }

    @Test
    void firstAuthorizePersistsAggregateAndEmitsOutboxEvent() {
        AuthorizeCommand cmd = newCommand("key-1", "a".repeat(64));

        ServiceResult result = service.authorize(cmd);

        assertThat(result).isInstanceOf(ServiceResult.Created.class);
        UUID authId = result.resourceId();
        assertThat(authId).isNotNull();

        assertThat(authorizations.findById(new AuthorizationId(authId))).hasValueSatisfying(a -> {
            assertThat(a.status()).isInstanceOf(AuthorizationStatus.Authorized.class);
            assertThat(a.authorizedAmount().currency()).isEqualTo("USD");
        });

        List<OutboxEntry> events = outboxRepo.findByAggregateId(authId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventType()).isEqualTo("PaymentAuthorized");
        assertThat(events.get(0).aggregateType()).isEqualTo("authorization");
    }

    @Test
    void replayingSameKeyAndHashReturnsCachedBodyAndDoesNotDoubleWrite() {
        AuthorizeCommand cmd = newCommand("key-replay", "a".repeat(64));
        ServiceResult first = service.authorize(cmd);

        ServiceResult second = service.authorize(cmd);

        assertThat(second).isInstanceOfSatisfying(ServiceResult.Replayed.class, r -> {
            assertThat(r.body()).isEqualTo(first.body());
            assertThat(r.resourceId()).isEqualTo(first.resourceId());
        });
        // Only one outbox event from the original authorize
        assertThat(outboxRepo.findByAggregateId(first.resourceId())).hasSize(1);
    }

    @Test
    void sameKeyDifferentHashReturnsHashMismatch() {
        AuthorizeCommand first = newCommand("key-conflict", "a".repeat(64));
        ServiceResult firstResult = service.authorize(first);
        AuthorizeCommand sameKeyDifferentHash = new AuthorizeCommand(
                first.merchantId(),
                first.amount(),
                first.expiresAt(),
                first.idempotencyKey(),
                "b".repeat(64));

        ServiceResult result = service.authorize(sameKeyDifferentHash);

        assertThat(result).isInstanceOf(ServiceResult.IdempotencyHashMismatch.class);
        // Still exactly one authorization in the table
        assertThat(authorizations.findById(new AuthorizationId(firstResult.resourceId())))
                .isPresent();
        assertThat(outboxRepo.findByAggregateId(firstResult.resourceId())).hasSize(1);
    }

    private static AuthorizeCommand newCommand(String key, String hash) {
        return new AuthorizeCommand(
                new MerchantId(UUID.randomUUID()),
                new Money(new BigDecimal("100.00"), "USD"),
                Instant.parse("2026-05-19T10:00:00Z"),
                key,
                hash);
    }
}
