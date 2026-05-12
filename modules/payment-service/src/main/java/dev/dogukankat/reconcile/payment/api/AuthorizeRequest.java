package dev.dogukankat.reconcile.payment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record AuthorizeRequest(
        @NotNull UUID merchantId,
        @NotBlank String amount,
        @NotBlank String currency,
        @NotNull Instant expiresAt) {
}
