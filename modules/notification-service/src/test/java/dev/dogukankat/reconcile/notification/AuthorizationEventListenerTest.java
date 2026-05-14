package dev.dogukankat.reconcile.notification;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

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
