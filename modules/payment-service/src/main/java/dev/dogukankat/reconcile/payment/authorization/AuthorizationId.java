package dev.dogukankat.reconcile.payment.authorization;

import java.util.Objects;
import java.util.UUID;

public record AuthorizationId(UUID value) {

    public AuthorizationId {
        Objects.requireNonNull(value, "value");
    }

    public static AuthorizationId generate() {
        return new AuthorizationId(UUID.randomUUID());
    }
}
