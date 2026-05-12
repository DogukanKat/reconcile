package dev.dogukankat.reconcile.payment.api;

import jakarta.validation.constraints.NotBlank;

public record CaptureRequest(
        @NotBlank String amount,
        @NotBlank String currency) {
}
