package dev.dogukankat.reconcile.payment.authorization;

import java.time.Instant;

public sealed interface DomainEvent {

    Instant occurredAt();

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
