package dev.dogukankat.reconcile.payment.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

/**
 * Counters for the four outcomes a request can have at the
 * idempotency boundary (ADR-0006). Wired into {@code AuthorizationService}
 * and {@code RefundService} at the points where the
 * {@link dev.dogukankat.reconcile.payment.idempotency.IdempotencyResult}
 * is resolved.
 *
 * Labels are intentionally low-cardinality: one tag, {@code outcome},
 * with four discrete values. A future per-merchant breakdown would
 * blow cardinality past Prometheus' practical limits; if we ever
 * need it, the right answer is a separate, sampled counter.
 */
@Component
public class IdempotencyMetrics {

    public enum Outcome {
        INSERTED("inserted"),
        IN_PROGRESS("in_progress"),
        HASH_MISMATCH("hash_mismatch"),
        COMPLETED_REPLAY("completed_replay");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Counter inserted;
    private final Counter inProgress;
    private final Counter hashMismatch;
    private final Counter completedReplay;
    private final Counter retentionDeleted;

    public IdempotencyMetrics(MeterRegistry registry) {
        this.inserted = counter(registry, Outcome.INSERTED);
        this.inProgress = counter(registry, Outcome.IN_PROGRESS);
        this.hashMismatch = counter(registry, Outcome.HASH_MISMATCH);
        this.completedReplay = counter(registry, Outcome.COMPLETED_REPLAY);
        this.retentionDeleted = Counter.builder("reconcile_idempotency_retention_deleted_total")
                .description("Idempotency rows deleted by the daily retention job")
                .register(registry);
    }

    public void record(Outcome outcome) {
        switch (outcome) {
            case INSERTED -> inserted.increment();
            case IN_PROGRESS -> inProgress.increment();
            case HASH_MISMATCH -> hashMismatch.increment();
            case COMPLETED_REPLAY -> completedReplay.increment();
        }
    }

    public void recordRetentionDeleted(int count) {
        if (count > 0) {
            retentionDeleted.increment(count);
        }
    }

    private static Counter counter(MeterRegistry registry, Outcome outcome) {
        return Counter.builder("reconcile_idempotency_outcome_total")
                .description("Outcomes of the idempotency reservation step")
                .tag("outcome", outcome.label())
                .register(registry);
    }
}
