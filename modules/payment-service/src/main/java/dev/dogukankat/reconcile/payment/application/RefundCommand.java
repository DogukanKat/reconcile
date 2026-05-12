package dev.dogukankat.reconcile.payment.application;

import dev.dogukankat.reconcile.payment.authorization.CaptureId;
import dev.dogukankat.reconcile.payment.authorization.MerchantId;
import dev.dogukankat.reconcile.payment.authorization.Money;

import java.util.Objects;

public record RefundCommand(
        CaptureId captureId,
        MerchantId merchantId,
        Money amount,
        String idempotencyKey,
        String requestHash) {

    public RefundCommand {
        Objects.requireNonNull(captureId, "captureId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestHash, "requestHash");
        if (requestHash.length() != 64) {
            throw new IllegalArgumentException("requestHash must be 64-char SHA-256 hex");
        }
    }
}
