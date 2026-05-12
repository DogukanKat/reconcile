package dev.dogukankat.reconcile.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.dogukankat.reconcile.payment.authorization.Authorization;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationRepository;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationResult;
import dev.dogukankat.reconcile.payment.event.DomainEvent;
import dev.dogukankat.reconcile.payment.idempotency.IdempotencyKeyRepository;
import dev.dogukankat.reconcile.payment.idempotency.IdempotencyResult;
import dev.dogukankat.reconcile.payment.outbox.OutboxWriter;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Wires the four pieces ADR-0006 says belong in the same transaction:
 * idempotency reservation, aggregate transition, outbox write,
 * idempotency completion. Every method runs under @Transactional so a
 * failure anywhere rolls every row back together, including the
 * idempotency record — a future retry of the same key gets a fresh
 * attempt, not a stuck IN_PROGRESS marker.
 */
@Service
public class AuthorizationService {

    private final AuthorizationRepository authorizations;
    private final IdempotencyKeyRepository idempotency;
    private final OutboxWriter outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuthorizationService(
            AuthorizationRepository authorizations,
            IdempotencyKeyRepository idempotency,
            OutboxWriter outbox,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorizations = authorizations;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ServiceResult authorize(AuthorizeCommand command) {
        Instant now = clock.instant();
        IdempotencyResult reservation = idempotency.tryReserve(
                command.merchantId(),
                command.idempotencyKey(),
                command.requestHash(),
                now);

        return switch (reservation) {
            case IdempotencyResult.Inserted ignored -> doAuthorize(command, now);
            case IdempotencyResult.InProgress ignored ->
                    new ServiceResult.IdempotencyInProgress();
            case IdempotencyResult.HashMismatch ignored ->
                    new ServiceResult.IdempotencyHashMismatch();
            case IdempotencyResult.Completed c ->
                    new ServiceResult.Replayed(c.responseStatus(), c.responseBody(), c.resourceId());
        };
    }

    private ServiceResult doAuthorize(AuthorizeCommand command, Instant now) {
        Authorization initiated = Authorization.initiate(
                command.merchantId(),
                command.amount(),
                command.expiresAt(),
                now);
        // Phase 1 has no network simulator, so the authorize transition
        // succeeds in the same call. ADR-0001's INITIATED state lives in
        // memory here and never reaches the table; only AUTHORIZED is
        // persisted. When the simulator lands, this method splits and
        // the INITIATED save becomes real.
        AuthorizationResult result = initiated.authorize(now);
        Authorization authorized = result.next();
        authorizations.save(authorized);
        for (DomainEvent event : result.events()) {
            outbox.publish(event);
        }
        String body = serialize(toResponse(authorized));
        idempotency.complete(
                command.merchantId(),
                command.idempotencyKey(),
                201,
                body,
                authorized.id().value(),
                now);
        return new ServiceResult.Created(body, authorized.id().value());
    }

    private AuthorizationResponse toResponse(Authorization a) {
        return new AuthorizationResponse(
                a.id().value(),
                a.merchantId().value(),
                a.authorizedAmount().amount().toPlainString(),
                a.authorizedAmount().currency(),
                a.expiresAt(),
                a.status().getClass().getSimpleName());
    }

    private String serialize(AuthorizationResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "could not serialize authorization response", e);
        }
    }
}
