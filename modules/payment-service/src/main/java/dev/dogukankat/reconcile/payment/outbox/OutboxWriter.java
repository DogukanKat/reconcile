package dev.dogukankat.reconcile.payment.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.dogukankat.reconcile.payment.event.DomainEvent;

import io.confluent.kafka.serializers.KafkaAvroSerializer;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Translates a domain event into an outbox row and persists it. Meant
 * to be called inside the same transaction as the aggregate write so
 * the row only exists when the aggregate change does (ADR-0006's
 * composition guarantee).
 *
 * Phase 3 wire format (Option C, ADR-0009): {@code PaymentAuthorized}
 * is serialized to Confluent-framed Avro (magic byte + schema id +
 * Avro body) and its schema is registered under
 * {@code reconcile.authorization.v1-value}. The schema is the
 * {@code shared-events} contract by construction because the
 * application produced it, not a schema Debezium inferred from a
 * JSON column.
 *
 * The other event types that route to the same topic
 * ({@code PaymentCaptured}, {@code AuthorizationVoided},
 * {@code AuthorizationExpired}, ...) are not modelled in Avro yet
 * (deferred in Feature 02's scope, ADR-0009). They keep serializing
 * as JSON bytes. This is a deliberate incremental-migration state,
 * not a permanent mixed-format design: one event type proves the
 * Avro+registry+evolution path end to end; the rest get the same
 * treatment in a later phase. The outbox column is {@code BYTEA}, so
 * it carries either representation, and Debezium's
 * {@code ByteArrayConverter} passes whatever bytes are there through
 * untouched.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final KafkaAvroSerializer avroSerializer;

    public OutboxWriter(
            OutboxRepository repository,
            ObjectMapper objectMapper,
            KafkaAvroSerializer avroSerializer) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.avroSerializer = avroSerializer;
    }

    public OutboxEntry publish(DomainEvent event) {
        byte[] payload = serialize(event);
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

    private byte[] serialize(DomainEvent event) {
        if (event instanceof DomainEvent.PaymentAuthorized authorized) {
            // Topic must match Debezium's EventRouter routing template
            // (reconcile.${aggregatetype}.v1) so the registry subject
            // the serializer derives (TopicNameStrategy → <topic>-value)
            // is the subject a consumer of that topic resolves against.
            String topic = "reconcile." + event.aggregateType() + ".v1";
            return avroSerializer.serialize(
                    topic, PaymentAuthorizedAvroMapper.toAvro(authorized));
        }
        return serializeJson(event);
    }

    private byte[] serializeJson(DomainEvent event) {
        try {
            return objectMapper.writeValueAsBytes(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "could not serialize " + event.getClass().getSimpleName(), e);
        }
    }
}
