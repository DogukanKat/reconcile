package dev.dogukankat.reconcile.payment.application;

import dev.dogukankat.reconcile.payment.authorization.MerchantId;
import dev.dogukankat.reconcile.payment.authorization.Money;

import java.time.Instant;
import java.util.Objects;

public record AuthorizeCommand(
        MerchantId merchantId,
        Money amount,
        Instant expiresAt,
        String idempotencyKey,
        String requestHash) {

    public AuthorizeCommand {
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestHash, "requestHash");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (requestHash.length() != 64) {
            throw new IllegalArgumentException(
                    "requestHash must be hex-encoded SHA-256 (64 chars), got "
                            + requestHash.length());
        }
    }
}
