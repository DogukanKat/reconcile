package dev.dogukankat.reconcile.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Knobs for the consumer retry topic chain. Defaults align with the
 * draft of ADR-0008 (Feature 06): 1s / 3s / 9s, capped at 5 min, four
 * attempts total. Constructor validates so a misconfigured deploy
 * fails fast at startup rather than at the first retry.
 */
@ConfigurationProperties("reconcile.consumer.retry")
public record RetryProperties(
        Duration initialDelay,
        double multiplier,
        Duration maxDelay,
        int maxAttempts) {

    public RetryProperties {
        if (initialDelay == null || initialDelay.isZero() || initialDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "initialDelay must be positive: " + initialDelay);
        }
        if (multiplier <= 1.0) {
            throw new IllegalArgumentException(
                    "multiplier must be > 1.0 (was " + multiplier
                            + "); use a higher value or drop the retry topic");
        }
        if (maxDelay == null || maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException(
                    "maxDelay (" + maxDelay
                            + ") must be >= initialDelay (" + initialDelay + ")");
        }
        if (maxAttempts < 2) {
            throw new IllegalArgumentException(
                    "maxAttempts must be >= 2 (was " + maxAttempts
                            + "); 1 attempt means no retries");
        }
    }
}
