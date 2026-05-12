package dev.dogukankat.reconcile.payment.refund;

import dev.dogukankat.reconcile.payment.event.DomainEvent;

import java.util.List;
import java.util.Objects;

public record RefundResult(Refund next, List<DomainEvent> events) {

    public RefundResult {
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(events, "events");
        events = List.copyOf(events);
    }
}
