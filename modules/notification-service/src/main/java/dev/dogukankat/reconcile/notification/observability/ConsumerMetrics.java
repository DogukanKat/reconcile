package dev.dogukankat.reconcile.notification.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

/**
 * Counters for the consumer's failure path. The retry counter is
 * deliberately not exposed here: Spring Kafka's retry-topic
 * framework doesn't expose a public hook for "this record was just
 * retried", and the visible Kafka header for attempt count
 * (`kafka_attempt-count`) only lives on the retry topics, not on
 * the source topic listener. Counting retries by inspecting that
 * header on the retry-topic side would double-count anything that
 * eventually lands on DLT (every retry topic the record visited
 * plus the final DLT). ADR-0008 covers the choice.
 *
 * What we do count, by exception class:
 *
 * - {@code reconcile_consumer_dlq_total} — every DLT publish.
 *
 * Adding {@code _retry_total} cleanly is a Phase 3 follow-up
 * (probably via a {@code ConsumerRecordRecoverer} wrap in
 * {@code DltConfig}).
 */
@Component
public class ConsumerMetrics {

    private final MeterRegistry registry;

    public ConsumerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordDlq(String topic, String exceptionClass) {
        Counter.builder("reconcile_consumer_dlq_total")
                .description("Messages forwarded to the dead-letter topic")
                .tag("topic", topic)
                .tag("exception_class", exceptionClass)
                .register(registry)
                .increment();
    }
}
