package dev.dogukankat.reconcile.payment.application;

import java.util.UUID;

/**
 * What the service tells the HTTP boundary to send back. Sealed so
 * the controller's switch is exhaustive and any future outcome (e.g.
 * a deferred ACCEPTED state from a real network simulator) is a
 * conscious addition.
 */
public sealed interface ServiceResult {

    int httpStatus();

    String body();

    UUID resourceId();

    record Created(String body, UUID resourceId) implements ServiceResult {
        @Override
        public int httpStatus() {
            return 201;
        }
    }

    record Replayed(int httpStatus, String body, UUID resourceId) implements ServiceResult {}

    record IdempotencyInProgress() implements ServiceResult {
        @Override
        public int httpStatus() {
            return 409;
        }

        @Override
        public String body() {
            return "{\"error\":\"idempotency_request_in_progress\"}";
        }

        @Override
        public UUID resourceId() {
            return null;
        }
    }

    record IdempotencyHashMismatch() implements ServiceResult {
        @Override
        public int httpStatus() {
            return 409;
        }

        @Override
        public String body() {
            return "{\"error\":\"idempotency_key_reuse_with_different_parameters\"}";
        }

        @Override
        public UUID resourceId() {
            return null;
        }
    }
}
