package dev.dogukankat.reconcile.notification.dlt;

import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.KafkaHeaders;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * {@link DeadLetterPublishingRecoverer.ExceptionHeadersCreator}
 * implementation that writes the standard {@code kafka_dlt-*}
 * exception headers and truncates the stack trace to a configured
 * byte budget.
 *
 * Why we cap stack trace length: Kafka's default {@code
 * message.max.bytes} is 1 MB and a Java stack trace from a stuck
 * Spring proxy chain can blow past that surprisingly easily. 4 KB
 * is enough to identify the failure mode without bloating the
 * record. A human pulling a single bad message off the DLT for
 * inspection is the consumer; if they need the full trace, the
 * source logs still have it (the {@code correlationId} header is
 * preserved by the framework and lets them grep across services).
 *
 * Header names are the {@link KafkaHeaders} defaults; the
 * {@link DeadLetterPublishingRecoverer.HeaderNames} parameter is
 * intentionally ignored — this implementation does not honor custom
 * header renames. Phase 2 doesn't need them, and adding the
 * indirection now would be premature.
 */
public class TruncatingExceptionHeadersCreator
        implements DeadLetterPublishingRecoverer.ExceptionHeadersCreator {

    private final int maxStackTraceBytes;

    public TruncatingExceptionHeadersCreator(int maxStackTraceBytes) {
        if (maxStackTraceBytes < 256) {
            throw new IllegalArgumentException(
                    "maxStackTraceBytes must be >= 256 (was "
                            + maxStackTraceBytes + ")");
        }
        this.maxStackTraceBytes = maxStackTraceBytes;
    }

    @Override
    public void create(Headers kafkaHeaders, Exception exception,
                       boolean isKey,
                       DeadLetterPublishingRecoverer.HeaderNames headerNames) {
        String fqcn = exception.getClass().getName();
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        byte[] stackBytes = truncate(stackTraceOf(exception));

        kafkaHeaders.add(KafkaHeaders.DLT_EXCEPTION_FQCN,
                fqcn.getBytes(StandardCharsets.UTF_8));
        kafkaHeaders.add(KafkaHeaders.DLT_EXCEPTION_MESSAGE,
                message.getBytes(StandardCharsets.UTF_8));
        kafkaHeaders.add(KafkaHeaders.DLT_EXCEPTION_STACKTRACE, stackBytes);
        Throwable cause = exception.getCause();
        if (cause != null) {
            kafkaHeaders.add(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN,
                    cause.getClass().getName().getBytes(StandardCharsets.UTF_8));
        }
    }

    private byte[] truncate(String stackTrace) {
        byte[] bytes = stackTrace.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxStackTraceBytes) {
            return bytes;
        }
        byte[] truncated = new byte[maxStackTraceBytes];
        System.arraycopy(bytes, 0, truncated, 0, maxStackTraceBytes);
        return truncated;
    }

    private static String stackTraceOf(Throwable t) {
        StringWriter buffer = new StringWriter();
        try (PrintWriter writer = new PrintWriter(buffer)) {
            t.printStackTrace(writer);
        }
        return buffer.toString();
    }
}
