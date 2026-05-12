package dev.dogukankat.reconcile.payment.authorization;

import java.time.Instant;

public sealed interface DomainEvent {

    Instant occurredAt();

    /**
     * Every Phase 1 domain event belongs to an authorization aggregate
     * (captures and refunds-against-captures included). Declaring this
     * here lets the outbox writer pull the partition key without a
     * sealed switch on the event type.
     */
    AuthorizationId authorizationId();

    record PaymentAuthorized(
            AuthorizationId authorizationId,
            MerchantId merchantId,
            Money amount,
            Instant occurredAt) implements DomainEvent {}

    record AuthorizationFailed(
            AuthorizationId authorizationId,
            String reason,
            Instant occurredAt) implements DomainEvent {}

    record PaymentCaptured(
            AuthorizationId authorizationId,
            CaptureId captureId,
            Money amount,
            Instant occurredAt) implements DomainEvent {}

    record CaptureFailed(
            AuthorizationId authorizationId,
            CaptureId captureId,
            String reason,
            Instant occurredAt) implements DomainEvent {}

    record AuthorizationVoided(
            AuthorizationId authorizationId,
            Instant occurredAt) implements DomainEvent {}

    record AuthorizationExpired(
            AuthorizationId authorizationId,
            Instant occurredAt) implements DomainEvent {}
}
