package dev.dogukankat.reconcile.payment.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import dev.dogukankat.reconcile.payment.authorization.AuthorizationRepository;
import dev.dogukankat.reconcile.payment.authorization.MerchantId;
import dev.dogukankat.reconcile.payment.authorization.Money;
import dev.dogukankat.reconcile.payment.idempotency.IdempotencyKeyRepository;
import dev.dogukankat.reconcile.payment.idempotency.IdempotencyResult;
import dev.dogukankat.reconcile.payment.observability.IdempotencyMetrics;
import dev.dogukankat.reconcile.payment.outbox.OutboxWriter;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-12T10:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-05-19T10:00:00Z");
    private static final String HASH = "a".repeat(64);

    AuthorizationRepository authorizations;
    IdempotencyKeyRepository idempotency;
    OutboxWriter outbox;
    AuthorizationService service;

    @BeforeEach
    void setUp() {
        authorizations = mock(AuthorizationRepository.class);
        idempotency = mock(IdempotencyKeyRepository.class);
        outbox = mock(OutboxWriter.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new AuthorizationService(
                authorizations, idempotency, outbox, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new IdempotencyMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void insertedReservationCreatesAuthorization() {
        when(idempotency.tryReserve(any(), anyString(), anyString(), any()))
                .thenReturn(new IdempotencyResult.Inserted());

        ServiceResult result = service.authorize(command());

        assertThat(result).isInstanceOfSatisfying(ServiceResult.Created.class, c -> {
            assertThat(c.httpStatus()).isEqualTo(201);
            assertThat(c.body()).contains("\"AUTHORIZED\"");
            assertThat(c.resourceId()).isNotNull();
        });
        verify(authorizations).save(any());
        verify(outbox).publish(any());
        verify(idempotency).complete(any(), anyString(), anyInt(), anyString(), any(), any());
    }

    @Test
    void inProgressReservationReturnsConflictWithoutTouchingDomain() {
        when(idempotency.tryReserve(any(), anyString(), anyString(), any()))
                .thenReturn(new IdempotencyResult.InProgress());

        ServiceResult result = service.authorize(command());

        assertThat(result).isInstanceOf(ServiceResult.IdempotencyInProgress.class);
        assertThat(result.httpStatus()).isEqualTo(409);
        verify(authorizations, never()).save(any());
        verify(outbox, never()).publish(any());
    }

    @Test
    void hashMismatchReservationReturnsConflictWithoutTouchingDomain() {
        when(idempotency.tryReserve(any(), anyString(), anyString(), any()))
                .thenReturn(new IdempotencyResult.HashMismatch());

        ServiceResult result = service.authorize(command());

        assertThat(result).isInstanceOf(ServiceResult.IdempotencyHashMismatch.class);
        assertThat(result.httpStatus()).isEqualTo(409);
        verify(authorizations, never()).save(any());
        verify(outbox, never()).publish(any());
    }

    @Test
    void completedReservationReplaysCachedBody() {
        UUID cachedId = UUID.randomUUID();
        when(idempotency.tryReserve(any(), anyString(), anyString(), any()))
                .thenReturn(new IdempotencyResult.Completed(201, "{\"cached\":true}", cachedId));

        ServiceResult result = service.authorize(command());

        assertThat(result).isInstanceOfSatisfying(ServiceResult.Replayed.class, r -> {
            assertThat(r.httpStatus()).isEqualTo(201);
            assertThat(r.body()).isEqualTo("{\"cached\":true}");
            assertThat(r.resourceId()).isEqualTo(cachedId);
        });
        verify(authorizations, never()).save(any());
        verify(outbox, never()).publish(any());
    }

    private AuthorizeCommand command() {
        return new AuthorizeCommand(
                new MerchantId(UUID.randomUUID()),
                new Money(new BigDecimal("100.00"), "USD"),
                EXPIRES,
                "key-1",
                HASH);
    }
}
