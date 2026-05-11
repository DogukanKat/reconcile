package dev.dogukankat.reconcile.payment.authorization;

import java.util.List;
import java.util.Objects;

public record AuthorizationResult(Authorization next, List<DomainEvent> events) {

    public AuthorizationResult {
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(events, "events");
        events = List.copyOf(events);
    }
}
