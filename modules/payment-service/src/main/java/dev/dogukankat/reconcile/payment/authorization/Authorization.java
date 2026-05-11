package dev.dogukankat.reconcile.payment.authorization;

import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.AuthFailed;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.Authorized;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.Expired;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.Initiated;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.Voided;
import dev.dogukankat.reconcile.payment.authorization.DomainEvent.AuthorizationExpired;
import dev.dogukankat.reconcile.payment.authorization.DomainEvent.AuthorizationFailed;
import dev.dogukankat.reconcile.payment.authorization.DomainEvent.AuthorizationVoided;
import dev.dogukankat.reconcile.payment.authorization.DomainEvent.PaymentAuthorized;
import dev.dogukankat.reconcile.payment.authorization.DomainEvent.PaymentCaptured;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record Authorization(
        AuthorizationId id,
        MerchantId merchantId,
        Money authorizedAmount,
        Instant expiresAt,
        AuthorizationStatus status,
        List<Capture> captures) {

    public Authorization {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(authorizedAmount, "authorizedAmount");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(captures, "captures");
        if (!authorizedAmount.isPositive()) {
            throw new IllegalArgumentException(
                    "authorizedAmount must be positive: " + authorizedAmount);
        }
        captures = List.copyOf(captures);
    }

    public static Authorization initiate(
            MerchantId merchantId,
            Money amount,
            Instant expiresAt,
            Instant initiatedAt) {
        return new Authorization(
                AuthorizationId.generate(),
                merchantId,
                amount,
                expiresAt,
                new Initiated(initiatedAt),
                List.of());
    }

    public AuthorizationResult authorize(Instant authorizedAt) {
        requireStatus(Initiated.class, "authorize");
        Authorization next = withStatus(new Authorized(authorizedAt));
        return new AuthorizationResult(
                next,
                List.of(new PaymentAuthorized(id, merchantId, authorizedAmount, authorizedAt)));
    }

    public AuthorizationResult failAuthorization(String reason, Instant failedAt) {
        requireStatus(Initiated.class, "failAuthorization");
        Objects.requireNonNull(reason, "reason");
        Authorization next = withStatus(new AuthFailed(failedAt, reason));
        return new AuthorizationResult(
                next,
                List.of(new AuthorizationFailed(id, reason, failedAt)));
    }

    public AuthorizationResult voidAuthorization(Instant voidedAt) {
        requireStatus(Authorized.class, "void");
        if (!captures.isEmpty()) {
            throw new IllegalStateException(
                    "cannot void an authorization that has captures");
        }
        Authorization next = withStatus(new Voided(voidedAt));
        return new AuthorizationResult(
                next,
                List.of(new AuthorizationVoided(id, voidedAt)));
    }

    public AuthorizationResult expire(Instant expiredAt) {
        requireStatus(Authorized.class, "expire");
        if (!expiredAt.isAfter(expiresAt)) {
            throw new IllegalStateException(
                    "expire requires expiredAt > expiresAt; expiresAt="
                            + expiresAt + " expiredAt=" + expiredAt);
        }
        Authorization next = withStatus(new Expired(expiredAt));
        return new AuthorizationResult(
                next,
                List.of(new AuthorizationExpired(id, expiredAt)));
    }

    public AuthorizationResult capture(Money amount, Instant submittedAt) {
        requireStatus(Authorized.class, "capture");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("capture amount must be positive: " + amount);
        }
        Money succeededTotal = succeededCaptureTotal();
        Money totalAfter = succeededTotal.add(amount);
        if (!totalAfter.isLessThanOrEqualTo(authorizedAmount)) {
            throw new IllegalStateException(
                    "capture exceeds authorized capacity; authorized="
                            + authorizedAmount + " alreadyCaptured=" + succeededTotal
                            + " requested=" + amount);
        }
        Capture newCapture = new Capture(
                CaptureId.generate(), amount, CaptureStatus.PENDING, submittedAt, null);
        List<Capture> nextCaptures = new ArrayList<>(captures);
        nextCaptures.add(newCapture);
        Authorization next = new Authorization(
                id, merchantId, authorizedAmount, expiresAt, status, nextCaptures);
        return new AuthorizationResult(
                next,
                List.of(new PaymentCaptured(id, newCapture.id(), amount, submittedAt)));
    }

    private Money succeededCaptureTotal() {
        return captures.stream()
                .filter(c -> c.status() == CaptureStatus.SUCCEEDED)
                .map(Capture::amount)
                .reduce(Money.zero(authorizedAmount.currency()), Money::add);
    }

    private Authorization withStatus(AuthorizationStatus newStatus) {
        return new Authorization(
                id, merchantId, authorizedAmount, expiresAt, newStatus, captures);
    }

    private void requireStatus(Class<? extends AuthorizationStatus> expected, String op) {
        if (!expected.isInstance(status)) {
            throw new IllegalStateException(
                    op + " requires " + expected.getSimpleName()
                            + ", was " + status.getClass().getSimpleName());
        }
    }
}
