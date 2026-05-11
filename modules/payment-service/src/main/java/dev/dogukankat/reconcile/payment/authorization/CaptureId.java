package dev.dogukankat.reconcile.payment.authorization;

import java.util.Objects;
import java.util.UUID;

public record CaptureId(UUID value) {

    public CaptureId {
        Objects.requireNonNull(value, "value");
    }

    public static CaptureId generate() {
        return new CaptureId(UUID.randomUUID());
    }
}
