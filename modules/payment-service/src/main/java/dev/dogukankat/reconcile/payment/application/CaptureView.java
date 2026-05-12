package dev.dogukankat.reconcile.payment.application;

import dev.dogukankat.reconcile.payment.authorization.Capture;

import java.time.Instant;
import java.util.UUID;

public record CaptureView(
        UUID id,
        String amount,
        String currency,
        String status,
        Instant submittedAt,
        Instant completedAt) {

    public static CaptureView from(Capture c) {
        return new CaptureView(
                c.id().value(),
                c.amount().amount().toPlainString(),
                c.amount().currency(),
                c.status().name(),
                c.submittedAt(),
                c.completedAt());
    }
}
