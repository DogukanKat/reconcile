package dev.dogukankat.reconcile.payment.application;

import dev.dogukankat.reconcile.payment.authorization.AuthorizationId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Polls for authorizations whose expiry window has passed and flips
 * each one's status from AUTHORIZED to EXPIRED. Pure server-side
 * transition — no idempotency record is created because no client
 * retry is involved; the only caller is this scheduler.
 *
 * Each expire runs in its own transaction (AuthorizationService method
 * is @Transactional; called through the Spring proxy here). A failure
 * on one authorization logs and continues; the rest of the batch is
 * not affected.
 *
 * fixedRate=60s, initialDelay=30s keeps the first run from racing
 * application startup while still picking up newly-expired rows
 * within a minute of their expiresAt.
 */
@Component
public class AuthorizationExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationExpiryScheduler.class);

    private final AuthorizationService service;
    private final Clock clock;

    public AuthorizationExpiryScheduler(AuthorizationService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    public void expireOverdue() {
        Instant now = clock.instant();
        List<AuthorizationId> overdueIds = service.findExpiredAuthorized(now);
        if (overdueIds.isEmpty()) {
            return;
        }
        log.info("expiring {} overdue authorizations", overdueIds.size());
        int expired = 0;
        int failed = 0;
        for (AuthorizationId id : overdueIds) {
            try {
                service.expireAuthorization(id, now);
                expired++;
            } catch (Exception e) {
                failed++;
                log.warn("could not expire authorization {}: {}", id.value(), e.getMessage());
            }
        }
        log.info("expiry batch done: {} expired, {} failed", expired, failed);
    }
}
