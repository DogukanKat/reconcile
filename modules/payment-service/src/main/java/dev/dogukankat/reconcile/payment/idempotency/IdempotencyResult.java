package dev.dogukankat.reconcile.payment.idempotency;

import java.util.UUID;

/**
 * What happened when a request tried to reserve an idempotency key.
 * Maps directly to ADR-0006's four outcomes:
 *
 *   Inserted     — first time this key is seen; caller proceeds with the work.
 *   InProgress   — another request with the same key is mid-flight (409).
 *   Completed    — same key + same hash, cached response ready to replay.
 *   HashMismatch — same key, different body; signals a real client bug (409).
 */
public sealed interface IdempotencyResult {

    record Inserted() implements IdempotencyResult {}

    record InProgress() implements IdempotencyResult {}

    record HashMismatch() implements IdempotencyResult {}

    record Completed(
            int responseStatus,
            String responseBody,
            UUID resourceId) implements IdempotencyResult {}
}
