package dev.dogukankat.reconcile.payment.application;

import dev.dogukankat.reconcile.payment.authorization.AuthorizationId;
import dev.dogukankat.reconcile.payment.authorization.MerchantId;

import java.util.Objects;

public record VoidCommand(
        AuthorizationId authorizationId,
        MerchantId merchantId,
        String idempotencyKey,
        String requestHash) {

    public VoidCommand {
        Objects.requireNonNull(authorizationId, "authorizationId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestHash, "requestHash");
        if (requestHash.length() != 64) {
            throw new IllegalArgumentException("requestHash must be 64-char SHA-256 hex");
        }
    }
}
