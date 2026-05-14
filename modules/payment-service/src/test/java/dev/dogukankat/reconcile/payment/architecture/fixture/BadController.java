package dev.dogukankat.reconcile.payment.architecture.fixture;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Negative fixture: a controller that violates the
 * idempotency-header guardrail. Used by ArchitectureTest to prove the
 * rule actually catches the case it's supposed to catch.
 *
 * This class is intentionally placed outside {@code ..api..} so the
 * production-scope rule check doesn't pick it up.
 */
@RestController
public class BadController {

    @PostMapping("/oops")
    public ResponseEntity<String> mutateWithoutIdempotency() {
        return ResponseEntity.ok("oops");
    }
}
