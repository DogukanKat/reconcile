package dev.dogukankat.reconcile.payment.observability;

import dev.dogukankat.reconcile.payment.observability.IdempotencyMetrics.Outcome;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final IdempotencyMetrics metrics = new IdempotencyMetrics(registry);

    @Test
    void recordIncrementsTheTaggedCounter() {
        metrics.record(Outcome.INSERTED);
        metrics.record(Outcome.INSERTED);
        metrics.record(Outcome.HASH_MISMATCH);

        assertThat(counter("inserted")).isEqualTo(2.0);
        assertThat(counter("hash_mismatch")).isEqualTo(1.0);
        assertThat(counter("in_progress")).isEqualTo(0.0);
        assertThat(counter("completed_replay")).isEqualTo(0.0);
    }

    @Test
    void retentionDeletedSkipsZeroToAvoidNoiseInDashboards() {
        metrics.recordRetentionDeleted(0);
        metrics.recordRetentionDeleted(5);
        metrics.recordRetentionDeleted(3);

        assertThat(registry.counter("reconcile_idempotency_retention_deleted_total")
                .count())
                .isEqualTo(8.0);
    }

    private double counter(String outcomeLabel) {
        return registry.counter("reconcile_idempotency_outcome_total",
                "outcome", outcomeLabel).count();
    }
}
