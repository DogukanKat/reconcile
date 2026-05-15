package dev.dogukankat.reconcile.payment.outbox;

import dev.dogukankat.reconcile.payment.event.DomainEvent;

/**
 * Maps the domain {@link DomainEvent.PaymentAuthorized} to the
 * generated Avro record in {@code shared-events}. One place, so the
 * domain→wire field mapping is reviewable and unit-tested in
 * isolation rather than buried in the serializer call.
 *
 * The domain {@code Money.amount} is already scaled to
 * {@code Money.SCALE} (4) by its compact constructor, so it maps
 * straight onto the {@code decimal(18,4)} logical type with no
 * rounding here — rounding belongs in the domain, not on the way to
 * the wire. UUIDs go across as their canonical string form,
 * matching the Phase 1 JSON payload and ADR-0009's
 * string-over-uuid-logical-type call.
 */
final class PaymentAuthorizedAvroMapper {

    private PaymentAuthorizedAvroMapper() {
    }

    static dev.dogukankat.reconcile.events.PaymentAuthorized toAvro(
            DomainEvent.PaymentAuthorized event) {
        return dev.dogukankat.reconcile.events.PaymentAuthorized.newBuilder()
                .setAuthorizationId(event.authorizationId().value().toString())
                .setMerchantId(event.merchantId().value().toString())
                .setAmount(event.amount().amount())
                .setCurrency(event.amount().currency())
                .setOccurredAt(event.occurredAt())
                .build();
    }
}
