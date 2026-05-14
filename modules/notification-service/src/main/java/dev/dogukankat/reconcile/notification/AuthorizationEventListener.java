package dev.dogukankat.reconcile.notification;

import dev.dogukankat.reconcile.notification.error.NonRetryableConsumerException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * First consumer for the authorization stream. Logs every event for
 * now — the point is to prove the outbox-to-Kafka bridge round-trips
 * end to end. Real notification side-effects (email, push, webhooks)
 * arrive once the shape of those side-effects is more than a guess.
 *
 * The Debezium SMT puts the original outbox row id in the "id" header
 * and the event type in "eventType"; this service relies on those
 * plus the {@code correlationId} header for log stitching across the
 * write boundary. Consumers that need at-least-once dedup should
 * track which "id" they've already processed; this one is log-only so
 * duplicates are tolerable.
 */
@Component
public class AuthorizationEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationEventListener.class);
    private static final String MDC_CORRELATION_ID = "correlationId";

    @KafkaListener(topics = "reconcile.authorization.v1", groupId = "notification-service")
    public void onAuthorizationEvent(
            @Header(name = "eventType", required = false) byte[] eventType,
            @Header(name = "id", required = false) byte[] outboxId,
            @Header(name = "correlationId", required = false) byte[] correlationId,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Payload String payload) {
        if (eventType == null) {
            throw new NonRetryableConsumerException(
                    "missing required header: eventType");
        }
        if (outboxId == null) {
            throw new NonRetryableConsumerException(
                    "missing required header: id");
        }
        String type = new String(eventType, StandardCharsets.UTF_8);
        String outbox = new String(outboxId, StandardCharsets.UTF_8);
        boolean mdcBound = false;
        if (correlationId != null && correlationId.length > 0) {
            MDC.put(MDC_CORRELATION_ID, new String(correlationId, StandardCharsets.UTF_8));
            mdcBound = true;
        }
        try {
            log.info("authorization event: type={} authId={} outboxId={} payload={}",
                    type, key, outbox, payload);
        } finally {
            if (mdcBound) {
                MDC.remove(MDC_CORRELATION_ID);
            }
        }
    }

    /**
     * Logs every record that landed on the DLT with enough metadata
     * for a human to grep across services. This is the line that
     * pages someone — keep it structured and grep-friendly. The DLT
     * is terminal: nothing automatic pulls from it. Replay (if ever)
     * is a deliberate human action documented in failure-modes.md.
     */
    @DltHandler
    public void onDlt(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(name = KafkaHeaders.DLT_ORIGINAL_TOPIC, required = false) byte[] originalTopic,
            @Header(name = KafkaHeaders.DLT_ORIGINAL_PARTITION, required = false) byte[] originalPartition,
            @Header(name = KafkaHeaders.DLT_ORIGINAL_OFFSET, required = false) byte[] originalOffset,
            @Header(name = KafkaHeaders.DLT_EXCEPTION_FQCN, required = false) byte[] exceptionFqcn,
            @Header(name = KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false) byte[] exceptionMessage,
            @Header(name = "correlationId", required = false) byte[] correlationId) {
        boolean mdcBound = false;
        if (correlationId != null && correlationId.length > 0) {
            MDC.put(MDC_CORRELATION_ID, new String(correlationId, StandardCharsets.UTF_8));
            mdcBound = true;
        }
        try {
            log.error(
                    "consumer_dlt_received topic={} partition={} offset={} key={} exception={} message={} payload={}",
                    asString(originalTopic),
                    asInt(originalPartition),
                    asLong(originalOffset),
                    key,
                    asString(exceptionFqcn),
                    asString(exceptionMessage),
                    payload);
        } finally {
            if (mdcBound) {
                MDC.remove(MDC_CORRELATION_ID);
            }
        }
    }

    private static String asString(byte[] value) {
        return value == null ? "?" : new String(value, StandardCharsets.UTF_8);
    }

    private static String asInt(byte[] value) {
        return (value == null || value.length != Integer.BYTES)
                ? "?"
                : Integer.toString(ByteBuffer.wrap(value).getInt());
    }

    private static String asLong(byte[] value) {
        return (value == null || value.length != Long.BYTES)
                ? "?"
                : Long.toString(ByteBuffer.wrap(value).getLong());
    }
}
