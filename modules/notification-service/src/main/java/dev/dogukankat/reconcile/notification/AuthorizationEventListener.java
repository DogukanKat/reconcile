package dev.dogukankat.reconcile.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

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
        String type = eventType == null ? "?" : new String(eventType, StandardCharsets.UTF_8);
        String outbox = outboxId == null ? "?" : new String(outboxId, StandardCharsets.UTF_8);
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
}
