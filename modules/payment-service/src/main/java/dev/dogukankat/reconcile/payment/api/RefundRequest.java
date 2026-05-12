package dev.dogukankat.reconcile.payment.api;

import jakarta.validation.constraints.NotBlank;

public record RefundRequest(
        @NotBlank String amount,
        @NotBlank String currency) {
}
