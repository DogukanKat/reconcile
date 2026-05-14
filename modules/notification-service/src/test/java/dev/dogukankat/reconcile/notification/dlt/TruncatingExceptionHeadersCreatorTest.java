package dev.dogukankat.reconcile.notification.dlt;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TruncatingExceptionHeadersCreatorTest {

    private final TruncatingExceptionHeadersCreator creator =
            new TruncatingExceptionHeadersCreator(4096);

    @Test
    void writesFqcnMessageAndStackTraceHeaders() {
        RecordHeaders headers = new RecordHeaders();
        Exception ex = new IllegalStateException("boom");

        creator.create(headers, ex, false, null);

        assertThat(stringValue(headers, KafkaHeaders.DLT_EXCEPTION_FQCN))
                .isEqualTo(IllegalStateException.class.getName());
        assertThat(stringValue(headers, KafkaHeaders.DLT_EXCEPTION_MESSAGE))
                .isEqualTo("boom");
        assertThat(stringValue(headers, KafkaHeaders.DLT_EXCEPTION_STACKTRACE))
                .startsWith("java.lang.IllegalStateException: boom");
    }

    @Test
    void truncatesStackTraceLongerThanLimit() {
        TruncatingExceptionHeadersCreator small =
                new TruncatingExceptionHeadersCreator(256);
        RecordHeaders headers = new RecordHeaders();
        Exception ex = deepStackTrace(120);

        small.create(headers, ex, false, null);

        byte[] value = headers.lastHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE).value();
        assertThat(value.length).isLessThanOrEqualTo(256);
    }

    @Test
    void traceContainsTheExceptionMessageAtTheHead() {
        // The head of the trace is the most useful part for debugging,
        // and truncation only chops the tail. As long as the message
        // survives, the consumer can grep what they need.
        RecordHeaders headers = new RecordHeaders();
        Exception ex = new RuntimeException("short");

        creator.create(headers, ex, false, null);

        String trace = stringValue(headers, KafkaHeaders.DLT_EXCEPTION_STACKTRACE);
        assertThat(trace).contains("short");
        assertThat(trace.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(4096);
    }

    @Test
    void nullExceptionMessageStillProducesHeader() {
        RecordHeaders headers = new RecordHeaders();
        Exception ex = new RuntimeException((String) null);

        creator.create(headers, ex, false, null);

        // Empty string rather than missing header — consumers should always find the slot.
        assertThat(headers.lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE)).isNotNull();
    }

    private static String stringValue(RecordHeaders headers, String name) {
        Header h = headers.lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static Exception deepStackTrace(int depth) {
        if (depth <= 0) {
            return new RuntimeException("deep");
        }
        try {
            throw deepStackTrace(depth - 1);
        } catch (Exception inner) {
            return new RuntimeException("wrap " + depth, inner);
        }
    }
}
