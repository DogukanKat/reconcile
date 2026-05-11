package dev.dogukankat.reconcile.payment.authorization;

import java.time.Instant;
import java.util.Objects;

public sealed interface AuthorizationStatus {

    Instant timestamp();

    record Initiated(Instant timestamp) implements AuthorizationStatus {
        public Initiated {
            Objects.requireNonNull(timestamp, "timestamp");
        }
    }

    record Authorized(Instant timestamp) implements AuthorizationStatus {
        public Authorized {
            Objects.requireNonNull(timestamp, "timestamp");
        }
    }

    record Voided(Instant timestamp) implements AuthorizationStatus {
        public Voided {
            Objects.requireNonNull(timestamp, "timestamp");
        }
    }

    record Expired(Instant timestamp) implements AuthorizationStatus {
        public Expired {
            Objects.requireNonNull(timestamp, "timestamp");
        }
    }

    record AuthFailed(Instant timestamp, String reason) implements AuthorizationStatus {
        public AuthFailed {
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
