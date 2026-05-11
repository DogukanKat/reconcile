package dev.dogukankat.reconcile.payment.authorization;

import java.time.Instant;
import java.util.Objects;

public record Capture(
        CaptureId id,
        Money amount,
        CaptureStatus status,
        Instant submittedAt,
        Instant completedAt) {

    public Capture {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(submittedAt, "submittedAt");
    }
}
