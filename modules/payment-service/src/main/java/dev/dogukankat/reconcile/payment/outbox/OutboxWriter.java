package dev.dogukankat.reconcile.payment.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.dogukankat.reconcile.payment.event.DomainEvent;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Translates a domain event into an outbox row and persists it. Meant
 * to be called inside the same transaction as the aggregate write so
 * the row only exists when the aggregate change does (ADR-0006's
 * composition guarantee).
 */
@Component
public class OutboxWriter {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public OutboxEntry publish(DomainEvent event) {
        String payload = serialize(event);
        OutboxEntry entry = new OutboxEntry(
                UUID.randomUUID(),
                event.aggregateType(),
                event.authorizationId().value(),
                event.getClass().getSimpleName(),
                payload,
                event.occurredAt(),
                MDC.get("correlationId"));
        repository.append(entry);
        return entry;
    }

    private String serialize(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "could not serialize " + event.getClass().getSimpleName(), e);
        }
    }
}
