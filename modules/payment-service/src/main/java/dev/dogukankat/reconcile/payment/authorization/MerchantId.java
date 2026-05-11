package dev.dogukankat.reconcile.payment.authorization;

import java.util.Objects;
import java.util.UUID;

public record MerchantId(UUID value) {

    public MerchantId {
        Objects.requireNonNull(value, "value");
    }
}
