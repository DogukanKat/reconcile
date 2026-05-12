package dev.dogukankat.reconcile.payment.application;

import dev.dogukankat.reconcile.payment.authorization.Authorization;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.AuthFailed;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.Authorized;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.Expired;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.Initiated;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.Voided;
import dev.dogukankat.reconcile.payment.authorization.Capture;
import dev.dogukankat.reconcile.payment.authorization.CaptureStatus;
import dev.dogukankat.reconcile.payment.authorization.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-side projection of an Authorization. Carries both the persisted
 * lifecycle status and the derived view of capture progress
 * (PARTIALLY_CAPTURED / FULLY_CAPTURED) that ADR-0002 says stays out
 * of the table and gets computed on read instead.
 */
public record AuthorizationView(
        UUID id,
        UUID merchantId,
        String authorizedAmount,
        String currency,
        Instant expiresAt,
        String status,
        String derivedStatus,
        String capturedTotal,
        List<CaptureView> captures) {

    public static AuthorizationView from(Authorization a) {
        return new AuthorizationView(
                a.id().value(),
                a.merchantId().value(),
                a.authorizedAmount().amount().toPlainString(),
                a.authorizedAmount().currency(),
                a.expiresAt(),
                baseStatus(a),
                derivedStatus(a),
                succeededCaptureTotal(a).amount().toPlainString(),
                a.captures().stream().map(CaptureView::from).toList());
    }

    private static String baseStatus(Authorization a) {
        return switch (a.status()) {
            case Initiated i -> "INITIATED";
            case Authorized auth -> "AUTHORIZED";
            case Voided v -> "VOIDED";
            case Expired e -> "EXPIRED";
            case AuthFailed af -> "AUTH_FAILED";
        };
    }

    private static String derivedStatus(Authorization a) {
        if (!(a.status() instanceof Authorized)) {
            return baseStatus(a);
        }
        BigDecimal captured = succeededCaptureTotal(a).amount();
        if (captured.signum() == 0) {
            return "AUTHORIZED";
        }
        if (captured.compareTo(a.authorizedAmount().amount()) == 0) {
            return "FULLY_CAPTURED";
        }
        return "PARTIALLY_CAPTURED";
    }

    private static Money succeededCaptureTotal(Authorization a) {
        return a.captures().stream()
                .filter(c -> c.status() == CaptureStatus.SUCCEEDED)
                .map(Capture::amount)
                .reduce(Money.zero(a.authorizedAmount().currency()), Money::add);
    }
}
