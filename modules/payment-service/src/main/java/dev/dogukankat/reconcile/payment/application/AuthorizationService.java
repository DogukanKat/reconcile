package dev.dogukankat.reconcile.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.dogukankat.reconcile.payment.authorization.Authorization;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationId;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationRepository;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationResult;
import dev.dogukankat.reconcile.payment.authorization.Capture;
import dev.dogukankat.reconcile.payment.authorization.MerchantId;
import dev.dogukankat.reconcile.payment.event.DomainEvent;
import dev.dogukankat.reconcile.payment.idempotency.IdempotencyKeyRepository;
import dev.dogukankat.reconcile.payment.idempotency.IdempotencyResult;
import dev.dogukankat.reconcile.payment.observability.IdempotencyMetrics;
import dev.dogukankat.reconcile.payment.observability.IdempotencyMetrics.Outcome;
import dev.dogukankat.reconcile.payment.outbox.OutboxWriter;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Stitches idempotency + aggregate + outbox into one transaction
 * (ADR-0006). Every mutating method follows the same shape: reserve
 * an idempotency key, run the aggregate transition if reservation was
 * fresh, publish the resulting events to the outbox, complete the
 * idempotency record with the response body. A failure anywhere
 * rolls all four operations back together — the idempotency record
 * vanishes, so a retry of the same key starts clean instead of
 * inheriting a stuck IN_PROGRESS marker.
 */
@Service
public class AuthorizationService {

    private final AuthorizationRepository authorizations;
    private final IdempotencyKeyRepository idempotency;
    private final OutboxWriter outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final IdempotencyMetrics metrics;

    public AuthorizationService(
            AuthorizationRepository authorizations,
            IdempotencyKeyRepository idempotency,
            OutboxWriter outbox,
            ObjectMapper objectMapper,
            Clock clock,
            IdempotencyMetrics metrics) {
        this.authorizations = authorizations;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Transactional
    public ServiceResult authorize(AuthorizeCommand command) {
        Instant now = clock.instant();
        return switch (reserve(command.merchantId(), command.idempotencyKey(), command.requestHash(), now)) {
            case IdempotencyResult.Inserted ignored -> doAuthorize(command, now);
            case IdempotencyResult.InProgress ignored -> new ServiceResult.IdempotencyInProgress();
            case IdempotencyResult.HashMismatch ignored -> new ServiceResult.IdempotencyHashMismatch();
            case IdempotencyResult.Completed c -> replay(c);
        };
    }

    @Transactional
    public ServiceResult capture(CaptureCommand command) {
        Instant now = clock.instant();
        return switch (reserve(command.merchantId(), command.idempotencyKey(), command.requestHash(), now)) {
            case IdempotencyResult.Inserted ignored -> doCapture(command, now);
            case IdempotencyResult.InProgress ignored -> new ServiceResult.IdempotencyInProgress();
            case IdempotencyResult.HashMismatch ignored -> new ServiceResult.IdempotencyHashMismatch();
            case IdempotencyResult.Completed c -> replay(c);
        };
    }

    @Transactional
    public ServiceResult voidAuthorization(VoidCommand command) {
        Instant now = clock.instant();
        return switch (reserve(command.merchantId(), command.idempotencyKey(), command.requestHash(), now)) {
            case IdempotencyResult.Inserted ignored -> doVoid(command, now);
            case IdempotencyResult.InProgress ignored -> new ServiceResult.IdempotencyInProgress();
            case IdempotencyResult.HashMismatch ignored -> new ServiceResult.IdempotencyHashMismatch();
            case IdempotencyResult.Completed c -> replay(c);
        };
    }

    @Transactional(readOnly = true)
    public Optional<AuthorizationView> findById(AuthorizationId id) {
        return authorizations.findById(id).map(AuthorizationView::from);
    }

    @Transactional(readOnly = true)
    public java.util.List<AuthorizationId> findExpiredAuthorized(Instant cutoff) {
        return authorizations.findExpiredAuthorizedBefore(cutoff);
    }

    /**
     * Expires a single overdue authorization in its own transaction.
     * Called by the scheduler row-by-row so one bad authorization
     * doesn't roll back the whole batch. No idempotency record — this
     * is server-driven, not a client retry path.
     */
    @Transactional
    public void expireAuthorization(AuthorizationId id, Instant now) {
        Authorization auth = authorizations.findById(id)
                .orElseThrow(() -> new IllegalStateException(
                        "authorization not found at expire time: " + id.value()));
        AuthorizationResult result = auth.expire(now);
        authorizations.save(result.next());
        for (DomainEvent event : result.events()) {
            outbox.publish(event);
        }
    }

    private ServiceResult doAuthorize(AuthorizeCommand command, Instant now) {
        Authorization initiated = Authorization.initiate(
                command.merchantId(), command.amount(), command.expiresAt(), now);
        AuthorizationResult result = initiated.authorize(now);
        return persistAndComplete(
                command.merchantId(), command.idempotencyKey(), result,
                AuthorizationView.from(result.next()),
                result.next().id().value(), now);
    }

    private ServiceResult doCapture(CaptureCommand command, Instant now) {
        Authorization auth = requireOwnedAuthorization(command.authorizationId(), command.merchantId());
        AuthorizationResult result = auth.capture(command.amount(), now);
        Authorization next = result.next();
        Capture newCapture = next.captures().get(next.captures().size() - 1);
        return persistAndComplete(
                command.merchantId(), command.idempotencyKey(), result,
                CaptureView.from(newCapture),
                newCapture.id().value(), now);
    }

    private ServiceResult doVoid(VoidCommand command, Instant now) {
        Authorization auth = requireOwnedAuthorization(command.authorizationId(), command.merchantId());
        AuthorizationResult result = auth.voidAuthorization(now);
        return persistAndComplete(
                command.merchantId(), command.idempotencyKey(), result,
                AuthorizationView.from(result.next()),
                result.next().id().value(), now);
    }

    private ServiceResult persistAndComplete(
            MerchantId merchantId,
            String idempotencyKey,
            AuthorizationResult result,
            Object responsePayload,
            java.util.UUID resourceId,
            Instant now) {
        authorizations.save(result.next());
        for (DomainEvent event : result.events()) {
            outbox.publish(event);
        }
        String body = serialize(responsePayload);
        idempotency.complete(merchantId, idempotencyKey, 201, body, resourceId, now);
        return new ServiceResult.Created(body, resourceId);
    }

    private Authorization requireOwnedAuthorization(AuthorizationId id, MerchantId merchantId) {
        Authorization auth = authorizations.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "authorization not found: " + id.value()));
        if (!auth.merchantId().equals(merchantId)) {
            throw new IllegalArgumentException(
                    "authorization belongs to a different merchant");
        }
        return auth;
    }

    private IdempotencyResult reserve(
            MerchantId merchantId, String key, String hash, Instant now) {
        IdempotencyResult result = idempotency.tryReserve(merchantId, key, hash, now);
        metrics.record(outcomeOf(result));
        return result;
    }

    private static Outcome outcomeOf(IdempotencyResult result) {
        return switch (result) {
            case IdempotencyResult.Inserted ignored -> Outcome.INSERTED;
            case IdempotencyResult.InProgress ignored -> Outcome.IN_PROGRESS;
            case IdempotencyResult.HashMismatch ignored -> Outcome.HASH_MISMATCH;
            case IdempotencyResult.Completed ignored -> Outcome.COMPLETED_REPLAY;
        };
    }

    private static ServiceResult replay(IdempotencyResult.Completed c) {
        return new ServiceResult.Replayed(c.responseStatus(), c.responseBody(), c.resourceId());
    }

    private String serialize(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize response", e);
        }
    }
}
