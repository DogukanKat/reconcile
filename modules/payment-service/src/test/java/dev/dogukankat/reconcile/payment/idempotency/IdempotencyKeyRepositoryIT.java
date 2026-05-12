package dev.dogukankat.reconcile.payment.idempotency;

import dev.dogukankat.reconcile.payment.authorization.MerchantId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against the local Postgres that {@code make up} starts. Same
 * reasoning as AuthorizationRepositoryIT: Docker Desktop + Testcontainers
 * compatibility still open. Linux CI will swing back to {@code @Testcontainers}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class IdempotencyKeyRepositoryIT {

    @Autowired
    IdempotencyKeyRepository repository;

    @Autowired
    JdbcClient jdbc;

    private static final Instant T0 = Instant.parse("2026-05-12T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-05-12T10:00:05Z");
    private static final String HASH_A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @BeforeEach
    void wipeTable() {
        jdbc.sql("DELETE FROM idempotency_keys").update();
    }

    @Test
    void firstReservationReturnsInserted() {
        IdempotencyResult result = repository.tryReserve(
                merchant(), "key-1", HASH_A, T0);

        assertThat(result).isInstanceOf(IdempotencyResult.Inserted.class);
    }

    @Test
    void sameKeyWhileInProgressReturnsInProgress() {
        MerchantId m = merchant();
        repository.tryReserve(m, "key-1", HASH_A, T0);

        IdempotencyResult second = repository.tryReserve(m, "key-1", HASH_A, T1);

        assertThat(second).isInstanceOf(IdempotencyResult.InProgress.class);
    }

    @Test
    void sameKeyDifferentHashReturnsHashMismatch() {
        MerchantId m = merchant();
        repository.tryReserve(m, "key-1", HASH_A, T0);

        IdempotencyResult second = repository.tryReserve(m, "key-1", HASH_B, T1);

        assertThat(second).isInstanceOf(IdempotencyResult.HashMismatch.class);
    }

    @Test
    void differentMerchantsCanReuseTheSameKey() {
        repository.tryReserve(merchant(), "key-1", HASH_A, T0);

        IdempotencyResult other = repository.tryReserve(
                merchant(), "key-1", HASH_A, T0);

        assertThat(other).isInstanceOf(IdempotencyResult.Inserted.class);
    }

    @Test
    void completedReservationReplaysCachedResponse() {
        MerchantId m = merchant();
        UUID resourceId = UUID.randomUUID();
        repository.tryReserve(m, "key-1", HASH_A, T0);
        repository.complete(m, "key-1", 201, "{\"ok\":true}", resourceId, T1);

        IdempotencyResult second = repository.tryReserve(m, "key-1", HASH_A, T1);

        assertThat(second).isInstanceOfSatisfying(
                IdempotencyResult.Completed.class,
                c -> {
                    assertThat(c.responseStatus()).isEqualTo(201);
                    assertThat(c.responseBody()).contains("\"ok\"");
                    assertThat(c.resourceId()).isEqualTo(resourceId);
                });
    }

    @Test
    void completingAnUnknownReservationThrows() {
        assertThatThrownBy(() -> repository.complete(
                merchant(), "missing", 200, "{}", UUID.randomUUID(), T1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no IN_PROGRESS");
    }

    @Test
    void deleteOlderThanRemovesExpiredRows() {
        MerchantId m = merchant();
        repository.tryReserve(m, "old", HASH_A, Instant.parse("2026-05-10T00:00:00Z"));
        repository.tryReserve(m, "new", HASH_B, T0);

        int deleted = repository.deleteOlderThan(Instant.parse("2026-05-11T00:00:00Z"));

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.tryReserve(m, "new", HASH_B, T1))
                .isInstanceOf(IdempotencyResult.InProgress.class);
    }

    private static MerchantId merchant() {
        return new MerchantId(UUID.randomUUID());
    }
}
