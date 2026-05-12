package dev.dogukankat.reconcile.payment.application;

import dev.dogukankat.reconcile.payment.refund.Refund;
import dev.dogukankat.reconcile.payment.refund.RefundStatus.Failed;
import dev.dogukankat.reconcile.payment.refund.RefundStatus.Pending;
import dev.dogukankat.reconcile.payment.refund.RefundStatus.Succeeded;

import java.time.Instant;
import java.util.UUID;

public record RefundView(
        UUID id,
        UUID authorizationId,
        UUID captureId,
        String amount,
        String currency,
        String status,
        Instant statusTimestamp) {

    public static RefundView from(Refund r) {
        String status = switch (r.status()) {
            case Pending p -> "PENDING";
            case Succeeded s -> "SUCCEEDED";
            case Failed f -> "FAILED";
        };
        return new RefundView(
                r.id().value(),
                r.authorizationId().value(),
                r.captureId().value(),
                r.amount().amount().toPlainString(),
                r.amount().currency(),
                status,
                r.status().timestamp());
    }
}
