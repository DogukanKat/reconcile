package dev.dogukankat.reconcile.payment.refund;

import java.time.Instant;
import java.util.Objects;

public sealed interface RefundStatus {

    Instant timestamp();

    record Pending(Instant timestamp) implements RefundStatus {
        public Pending {
            Objects.requireNonNull(timestamp, "timestamp");
        }
    }

    record Succeeded(Instant timestamp) implements RefundStatus {
        public Succeeded {
            Objects.requireNonNull(timestamp, "timestamp");
        }
    }

    record Failed(Instant timestamp, String reason) implements RefundStatus {
        public Failed {
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
