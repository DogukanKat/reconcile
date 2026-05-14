package dev.dogukankat.reconcile.notification;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import dev.dogukankat.reconcile.notification.error.NonRetryableConsumerException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationEventListenerTest {

    private final AuthorizationEventListener listener = new AuthorizationEventListener();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger =
            (Logger) LoggerFactory.getLogger(AuthorizationEventListener.class);

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        appender.stop();
        MDC.clear();
    }

    @Test
    void correlationIdHeaderIsBoundToMdcDuringHandlerAndClearedAfter() {
        listener.onAuthorizationEvent(
                "PaymentAuthorized".getBytes(StandardCharsets.UTF_8),
                "00000000-0000-0000-0000-000000000001".getBytes(StandardCharsets.UTF_8),
                "abc-123".getBytes(StandardCharsets.UTF_8),
                "auth-key",
                "{}");

        ILoggingEvent event = appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("authorization event"))
                .findFirst()
                .orElseThrow();
        assertThat(event.getMDCPropertyMap()).containsEntry("correlationId", "abc-123");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void dltHandlerLogsStructuredFailureLineAndBindsCorrelationIdToMdc() {
        listener.onDlt(
                "{\"foo\":\"bar\"}",
                "00000000-0000-0000-0000-000000000001",
                "reconcile.authorization.v1".getBytes(StandardCharsets.UTF_8),
                java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(2).array(),
                java.nio.ByteBuffer.allocate(Long.BYTES).putLong(42L).array(),
                "dev.dogukankat.reconcile.notification.error.NonRetryableConsumerException"
                        .getBytes(StandardCharsets.UTF_8),
                "missing required header: eventType".getBytes(StandardCharsets.UTF_8),
                "corr-dlt-1".getBytes(StandardCharsets.UTF_8));

        ch.qos.logback.classic.spi.ILoggingEvent event = appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("consumer_dlt_received"))
                .findFirst()
                .orElseThrow();
        assertThat(event.getMDCPropertyMap())
                .containsEntry("correlationId", "corr-dlt-1");
        assertThat(event.getFormattedMessage())
                .contains("topic=reconcile.authorization.v1")
                .contains("partition=2")
                .contains("offset=42")
                .contains("NonRetryableConsumerException")
                .contains("missing required header: eventType");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void missingEventTypeHeaderThrowsNonRetryable() {
        assertThatThrownBy(() -> listener.onAuthorizationEvent(
                null,
                "00000000-0000-0000-0000-000000000001".getBytes(StandardCharsets.UTF_8),
                "abc-123".getBytes(StandardCharsets.UTF_8),
                "auth-key",
                "{}"))
                .isInstanceOf(NonRetryableConsumerException.class)
                .hasMessageContaining("eventType");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void missingOutboxIdHeaderThrowsNonRetryable() {
        assertThatThrownBy(() -> listener.onAuthorizationEvent(
                "PaymentAuthorized".getBytes(StandardCharsets.UTF_8),
                null,
                "abc-123".getBytes(StandardCharsets.UTF_8),
                "auth-key",
                "{}"))
                .isInstanceOf(NonRetryableConsumerException.class)
                .hasMessageContaining("id");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void missingCorrelationIdHeaderLeavesMdcEmpty() {
        listener.onAuthorizationEvent(
                "PaymentAuthorized".getBytes(StandardCharsets.UTF_8),
                "00000000-0000-0000-0000-000000000001".getBytes(StandardCharsets.UTF_8),
                null,
                "auth-key",
                "{}");

        ILoggingEvent event = appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("authorization event"))
                .findFirst()
                .orElseThrow();
        assertThat(event.getMDCPropertyMap()).doesNotContainKey("correlationId");
        assertThat(MDC.get("correlationId")).isNull();
    }
}
