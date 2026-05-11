package dev.dogukankat.reconcile.payment.authorization;

import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.AuthFailed;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.Authorized;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.Initiated;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Disabled("Docker Desktop 4.72 + docker-java API: /info returns Status 400 with "
        + "an empty body, so Testcontainers can't validate the daemon. "
        + "Tracked in docs/notes-from-the-build.md; re-enable once "
        + "compatibility is resolved.")
class AuthorizationRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.4")
                    .withDatabaseName("reconcile")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    AuthorizationRepository repository;

    private static final Instant T0 = Instant.parse("2026-05-11T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-05-11T10:05:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-05-18T10:00:00Z");

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
