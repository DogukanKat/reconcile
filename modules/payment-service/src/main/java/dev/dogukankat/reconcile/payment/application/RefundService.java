package dev.dogukankat.reconcile.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.dogukankat.reconcile.payment.authorization.Authorization;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationRepository;
import dev.dogukankat.reconcile.payment.authorization.Capture;
import dev.dogukankat.reconcile.payment.authorization.CaptureId;
import dev.dogukankat.reconcile.payment.authorization.CaptureStatus;
import dev.dogukankat.reconcile.payment.authorization.MerchantId;
import dev.dogukankat.reconcile.payment.event.DomainEvent;
import dev.dogukankat.reconcile.payment.idempotency.IdempotencyKeyRepository;
import dev.dogukankat.reconcile.payment.idempotency.IdempotencyResult;
import dev.dogukankat.reconcile.payment.outbox.OutboxWriter;
import dev.dogukankat.reconcile.payment.refund.Refund;
import dev.dogukankat.reconcile.payment.refund.RefundRepository;
import dev.dogukankat.reconcile.payment.refund.RefundResult;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Separate service because Refund is a separate aggregate (ADR-0001).
 * Shares the idempotency + outbox plumbing but its own logic loads
 * the parent authorization to find the capture, sums existing refunds
 * to enforce the cross-aggregate invariant, then writes the refund
 * row itself.
 *
 * The parent capture's amount is used as the invariant boundary; this
 * is ADR-0001's "load-bearing assumption" that capture amounts are
 * immutable post-SUCCEEDED.
 */
@Service
public class RefundService {

    private final AuthorizationRepository authorizations;
    private final RefundRepository refunds;
    private final IdempotencyKeyRepository idempotency;
    private final OutboxWriter outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RefundService(
            AuthorizationRepository authorizations,
            RefundRepository refunds,
            IdempotencyKeyRepository idempotency,
            OutboxWriter outbox,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorizations = authorizations;
        this.refunds = refunds;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ServiceResult refund(RefundCommand command) {
        Instant now = clock.instant();
        IdempotencyResult reservation = idempotency.tryReserve(
                command.merchantId(),
                command.idempotencyKey(),
                command.requestHash(),
                now);

        return switch (reservation) {
            case IdempotencyResult.Inserted ignored -> doRefund(command, now);
            case IdempotencyResult.InProgress ignored -> new ServiceResult.IdempotencyInProgress();
            case IdempotencyResult.HashMismatch ignored -> new ServiceResult.IdempotencyHashMismatch();
            case IdempotencyResult.Completed c ->
                    new ServiceResult.Replayed(c.responseStatus(), c.responseBody(), c.resourceId());
        };
    }

    private ServiceResult doRefund(RefundCommand command, Instant now) {
        // Find the capture by walking the parent authorization.
        // Captures don't have their own table-level lookup; ADR-0001
        // keeps them inside the Authorization aggregate.
        Capture capture = locateCapture(command.captureId(), command.merchantId());
        if (capture.status() != CaptureStatus.SUCCEEDED) {
            throw new IllegalStateException(
                    "refund requires the parent capture to be SUCCEEDED, was "
                            + capture.status());
        }
        Authorization parent = authorizations.findByCaptureId(command.captureId()).orElseThrow();

        Refund refund = Refund.initiate(
                parent.id(),
                capture.id(),
                capture.amount(),
                refunds.succeededTotalForCapture(capture.id(), capture.amount().currency()),
                command.amount(),
                now);
        RefundResult result = refund.succeed(now);   // Phase 1: no network simulator
        Refund persisted = result.next();
        refunds.save(persisted);
        for (DomainEvent event : result.events()) {
            outbox.publish(event);
        }
        String body = serialize(RefundView.from(persisted));
        idempotency.complete(
                command.merchantId(),
                command.idempotencyKey(),
                201,
                body,
                persisted.id().value(),
                now);
        return new ServiceResult.Created(body, persisted.id().value());
    }

    private Capture locateCapture(CaptureId captureId, MerchantId merchantId) {
        Authorization auth = authorizations.findByCaptureId(captureId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "capture not found: " + captureId.value()));
        if (!auth.merchantId().equals(merchantId)) {
            throw new IllegalArgumentException(
                    "capture belongs to a different merchant");
        }
        return auth.captures().stream()
                .filter(c -> c.id().equals(captureId))
                .findFirst()
                .orElseThrow();
    }

    private String serialize(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize refund response", e);
        }
    }
}
