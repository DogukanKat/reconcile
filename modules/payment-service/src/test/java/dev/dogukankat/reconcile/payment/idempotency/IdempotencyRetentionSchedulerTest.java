package dev.dogukankat.reconcile.payment.idempotency;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyRetentionSchedulerTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-13T03:00:00Z");

    @Test
    void cleanupDeletesRowsOlderThan24Hours() {
        AtomicReference<Instant> seenCutoff = new AtomicReference<>();
        IdempotencyKeyRepository repo = new CapturingRepository(seenCutoff, 7);
        IdempotencyRetentionScheduler scheduler = new IdempotencyRetentionScheduler(
                repo, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        int deleted = scheduler.cleanup();

        assertThat(deleted).isEqualTo(7);
        assertThat(seenCutoff.get()).isEqualTo(FIXED_NOW.minus(Duration.ofHours(24)));
    }

    @Test
    void cleanupReturnsZeroWhenNothingToDelete() {
        IdempotencyKeyRepository repo = new CapturingRepository(new AtomicReference<>(), 0);
        IdempotencyRetentionScheduler scheduler = new IdempotencyRetentionScheduler(
                repo, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        assertThat(scheduler.cleanup()).isZero();
    }

    private static final class CapturingRepository extends IdempotencyKeyRepository {
        private final AtomicReference<Instant> seenCutoff;
        private final int deleted;

        CapturingRepository(AtomicReference<Instant> seenCutoff, int deleted) {
            super(null);
            this.seenCutoff = seenCutoff;
            this.deleted = deleted;
        }

        @Override
        public int deleteOlderThan(Instant cutoff) {
            seenCutoff.set(cutoff);
            return deleted;
        }
    }
}
