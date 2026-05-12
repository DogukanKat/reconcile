package dev.dogukankat.reconcile.payment.refund;

import java.util.Objects;
import java.util.UUID;

public record RefundId(UUID value) {

    public RefundId {
        Objects.requireNonNull(value, "value");
    }

    public static RefundId generate() {
        return new RefundId(UUID.randomUUID());
    }
}
