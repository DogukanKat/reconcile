package dev.dogukankat.reconcile.notification.error;

/**
 * Marker exception for consumer failures that won't get better by
 * waiting: deserialization failures, schema mismatches, business
 * invariant violations, malformed headers, unknown event types. Throw
 * this to send the message straight to the dead-letter topic on first
 * failure once Feature 04 wires the DLQ recoverer.
 */
public final class NonRetryableConsumerException extends RuntimeException {

    public NonRetryableConsumerException(String message) {
        super(message);
    }

    public NonRetryableConsumerException(String message, Throwable cause) {
        super(message, cause);
    }
}
