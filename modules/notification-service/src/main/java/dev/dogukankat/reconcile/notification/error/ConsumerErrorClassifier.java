package dev.dogukankat.reconcile.notification.error;

import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.util.Objects;

/**
 * Splits consumer failures into RETRYABLE and NON_RETRYABLE so the
 * retry-topic chain knows what to do with each. The defaults are
 * conservative: anything we haven't explicitly marked as retryable is
 * non-retryable. The cost of retrying a real bug forever is higher
 * than the cost of failing fast and surfacing it through the DLQ
 * (ADR-0008, Feature 06).
 *
 * Explicit retryable cases:
 *
 * - {@link RetryableConsumerException} — caller stated the failure
 *   is transient and we trust them even if the cause chain looks
 *   non-retryable.
 * - A {@link SocketTimeoutException} anywhere in the cause chain —
 *   network glitches almost always recover after a backoff.
 *
 * Everything else, including unknown {@link RuntimeException}s, is
 * treated as non-retryable.
 */
@Component
public class ConsumerErrorClassifier {

    public Classification classify(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");
        if (throwable instanceof RetryableConsumerException) {
            return Classification.RETRYABLE;
        }
        if (hasCauseOfType(throwable, SocketTimeoutException.class)) {
            return Classification.RETRYABLE;
        }
        return Classification.NON_RETRYABLE;
    }

    private static boolean hasCauseOfType(Throwable t, Class<? extends Throwable> type) {
        Throwable cursor = t;
        while (cursor != null) {
            if (type.isInstance(cursor)) {
                return true;
            }
            Throwable cause = cursor.getCause();
            if (cause == cursor) {
                return false;
            }
            cursor = cause;
        }
        return false;
    }
}
