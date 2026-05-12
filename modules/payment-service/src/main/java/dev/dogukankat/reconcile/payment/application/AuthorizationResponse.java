package dev.dogukankat.reconcile.payment.application;

import java.time.Instant;
import java.util.UUID;

/**
 * What the service tells the API to render back to the caller.
 * Amount is a string because JSON has no decimal type and a float
 * would lose precision; clients reconstruct a BigDecimal on the
 * other side.
 */
public record AuthorizationResponse(
        UUID id,
        UUID merchantId,
        String amount,
        String currency,
        Instant expiresAt,
        String status) {
}
