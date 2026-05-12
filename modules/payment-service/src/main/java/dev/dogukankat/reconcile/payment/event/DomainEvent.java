package dev.dogukankat.reconcile.payment.event;

import dev.dogukankat.reconcile.payment.authorization.AuthorizationId;
import dev.dogukankat.reconcile.payment.authorization.CaptureId;
import dev.dogukankat.reconcile.payment.authorization.MerchantId;
import dev.dogukankat.reconcile.payment.authorization.Money;
import dev.dogukankat.reconcile.payment.refund.RefundId;

import java.time.Instant;

/**
 * Lives in a common package so both aggregate roots can refer to the
 * same hierarchy. authorizationId() stays the partition key (ADR-0005)
 * even for refund events — every event for an authorization, including
 * its refunds, lands on the same Kafka partition.
 *
 * aggregateType() drives the Kafka topic per ADR-0005. Defaults to
 * "authorization"; refund events override.
 */
public sealed interface DomainEvent {

    Instant occurredAt();

    AuthorizationId authorizationId();

    default String aggregateType() {
        return "authorization";
    }

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

    record PaymentRefunded(
            RefundId refundId,
            CaptureId captureId,
            AuthorizationId authorizationId,
            Money amount,
            Instant occurredAt) implements DomainEvent {
        @Override
        public String aggregateType() {
            return "refund";
        }
    }

    record RefundFailed(
            RefundId refundId,
            CaptureId captureId,
            AuthorizationId authorizationId,
            String reason,
            Instant occurredAt) implements DomainEvent {
        @Override
        public String aggregateType() {
            return "refund";
        }
    }
}
