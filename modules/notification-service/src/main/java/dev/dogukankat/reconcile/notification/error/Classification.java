package dev.dogukankat.reconcile.notification.error;

/**
 * The two outcomes a consumer error can have. Retry semantics live in
 * Feature 03's retry topic chain; this enum is the contract that
 * tells the chain whether to put a record back on retry or send it
 * straight to DLQ.
 */
public enum Classification {
    RETRYABLE,
    NON_RETRYABLE
}
