package dev.dogukankat.reconcile.notification.error;

/**
 * Marker exception for consumer failures whose cause is plausibly
 * transient: a downstream 5xx, a broker hiccup, a lock timeout. Throw
 * this — wrapping the original cause — to route the message through
 * the retry topic chain that Feature 03 wires up. The classifier
 * trusts the thrower's claim even if a non-retryable exception is
 * nested as a cause; if you wrapped it explicitly, you meant it.
 */
public final class RetryableConsumerException extends RuntimeException {

    public RetryableConsumerException(String message, Throwable cause) {
        super(message, cause);
    }
}
