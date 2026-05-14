package dev.dogukankat.reconcile.payment.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Daily cleanup of completed idempotency rows past the 24-hour retention
 * window described in ADR-0006. The window is large enough to cover any
 * sane client retry budget; rows older than that are dead weight in the
 * unique-index path and we drop them.
 *
 * The repository's deleteOlderThan takes any cutoff; the policy lives
 * here so a future change (e.g. shorter retention for one merchant)
 * doesn't have to touch persistence code.
 */
@Component
public class IdempotencyRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyRetentionScheduler.class);
    private static final Duration RETENTION = Duration.ofHours(24);

    private final IdempotencyKeyRepository repository;
    private final Clock clock;

    public IdempotencyRetentionScheduler(IdempotencyKeyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public int cleanup() {
        Instant cutoff = clock.instant().minus(RETENTION);
        int deleted = repository.deleteOlderThan(cutoff);
        log.info("cleaned_idempotency_keys count={} cutoff={}", deleted, cutoff);
        return deleted;
    }
}
