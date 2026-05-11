package dev.dogukankat.reconcile.payment.authorization;

import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.AuthFailed;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.Authorized;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.Initiated;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against the local Postgres started by {@code make up} on the
 * standard {@code application.yml} datasource. Earlier turns tried
 * Testcontainers, but Docker Desktop 4.72's daemon refuses the API
 * version that the testcontainers/docker-java pair sends, and no
 * env-var or system-property override moved that needle. CI on Linux
 * doesn't hit the same wall; Testcontainers is the right tool there
 * once we wire up the pipeline.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuthorizationRepositoryIT {

    @Autowired
    AuthorizationRepository repository;

    @Autowired
    JdbcClient jdbc;

    private static final Instant T0 = Instant.parse("2026-05-11T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-05-11T10:05:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-05-18T10:00:00Z");

    @BeforeEach
    void wipeTables() {
        // captures is wiped via the FK's ON DELETE CASCADE.
        jdbc.sql("DELETE FROM authorizations").update();
    }

    @Test
    void savesAndLoadsInitiatedAuthorization() {
        Authorization a = newInitiated();
        repository.save(a);

        Optional<Authorization> loaded = repository.findById(a.id());

        assertThat(loaded).isPresent();
        assertThat(loaded.get().id()).isEqualTo(a.id());
        assertThat(loaded.get().merchantId()).isEqualTo(a.merchantId());
        assertThat(loaded.get().authorizedAmount()).isEqualTo(a.authorizedAmount());
        assertThat(loaded.get().status()).isInstanceOf(Initiated.class);
        assertThat(loaded.get().captures()).isEmpty();
    }

    @Test
    void updatesStatusOnReSave() {
        Authorization a = newInitiated();
        repository.save(a);

        Authorization authorized = a.authorize(T1).next();
        repository.save(authorized);

        Authorization loaded = repository.findById(a.id()).orElseThrow();
        assertThat(loaded.status()).isInstanceOf(Authorized.class);
    }

    @Test
    void persistsAuthFailedWithReason() {
        Authorization a = newInitiated();
        repository.save(a);

        Authorization failed = a.failAuthorization("declined by network", T1).next();
        repository.save(failed);

        Authorization loaded = repository.findById(a.id()).orElseThrow();
        assertThat(loaded.status()).isInstanceOf(AuthFailed.class);
        assertThat(((AuthFailed) loaded.status()).reason()).isEqualTo("declined by network");
    }

    @Test
    void persistsCapturesAndPreservesIdentity() {
        Authorization authorized = newInitiated().authorize(T1).next();
        Authorization withCapture = authorized
                .capture(new Money(new BigDecimal("30.00"), "USD"), T1).next();
        repository.save(withCapture);

        Authorization loaded = repository.findById(withCapture.id()).orElseThrow();

        assertThat(loaded.captures()).hasSize(1);
        Capture original = withCapture.captures().get(0);
        Capture roundTripped = loaded.captures().get(0);
        assertThat(roundTripped.id()).isEqualTo(original.id());
        assertThat(roundTripped.amount()).isEqualTo(original.amount());
        assertThat(roundTripped.status()).isEqualTo(CaptureStatus.PENDING);
        assertThat(roundTripped.completedAt()).isNull();
    }

    @Test
    void deleteAndInsertReplacesCaptureList() {
        Authorization authorized = newInitiated().authorize(T1).next();
        Authorization firstCapture = authorized
                .capture(new Money(new BigDecimal("30.00"), "USD"), T1).next();
        repository.save(firstCapture);

        Authorization secondCapture = firstCapture
                .capture(new Money(new BigDecimal("20.00"), "USD"), T1).next();
        repository.save(secondCapture);

        Authorization loaded = repository.findById(authorized.id()).orElseThrow();

        assertThat(loaded.captures()).hasSize(2);
        List<CaptureId> expectedIds = secondCapture.captures().stream()
                .map(Capture::id).toList();
        assertThat(loaded.captures())
                .extracting(Capture::id)
                .containsExactlyInAnyOrderElementsOf(expectedIds);
    }

    @Test
    void returnsEmptyForUnknownId() {
        Optional<Authorization> loaded = repository.findById(AuthorizationId.generate());
        assertThat(loaded).isEmpty();
    }

    private static Authorization newInitiated() {
        return Authorization.initiate(
                new MerchantId(UUID.randomUUID()),
                new Money(new BigDecimal("100.00"), "USD"),
                EXPIRES_AT,
                T0);
    }
}
