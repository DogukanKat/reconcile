package dev.dogukankat.reconcile.payment.application;

import dev.dogukankat.reconcile.payment.authorization.Authorization;
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
 * Drives the scheduler's expireOverdue() directly so the test doesn't
 * have to wait for the fixedRate timer. Seeds an authorization with
 * expires_at in the past, runs the batch, asserts the row flipped to
 * EXPIRED and that a corresponding outbox event landed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuthorizationExpirySchedulerIT {

    @Autowired AuthorizationExpiryScheduler scheduler;
    @Autowired AuthorizationRepository authorizations;
    @Autowired OutboxRepository outbox;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void wipe() {
        jdbc.sql("DELETE FROM refunds").update();
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM idempotency_keys").update();
        jdbc.sql("DELETE FROM authorizations").update();
    }

    @Test
    void expiresAuthorizationsPastTheirExpiresAt() {
        Instant pastExpiry = Instant.now().minusSeconds(60);
        Authorization overdue = seedAuthorized(pastExpiry);

        scheduler.expireOverdue();

        Authorization loaded = authorizations.findById(overdue.id()).orElseThrow();
        assertThat(loaded.status()).isInstanceOf(AuthorizationStatus.Expired.class);

        List<OutboxEntry> events = outbox.findByAggregateId(overdue.id().value());
        assertThat(events)
                .extracting(OutboxEntry::eventType)
                .contains("AuthorizationExpired");
    }

    @Test
    void leavesAuthorizationsThatHaventExpiredAlone() {
        Instant futureExpiry = Instant.now().plusSeconds(3600);
        Authorization current = seedAuthorized(futureExpiry);

        scheduler.expireOverdue();

        Authorization loaded = authorizations.findById(current.id()).orElseThrow();
        assertThat(loaded.status()).isInstanceOf(AuthorizationStatus.Authorized.class);
    }

    private Authorization seedAuthorized(Instant expiresAt) {
        Authorization authorized = Authorization.initiate(
                        new MerchantId(UUID.randomUUID()),
                        new Money(new BigDecimal("100.00"), "USD"),
                        expiresAt,
                        Instant.now().minusSeconds(120))
                .authorize(Instant.now().minusSeconds(120)).next();
        authorizations.save(authorized);
        return authorized;
    }
}
