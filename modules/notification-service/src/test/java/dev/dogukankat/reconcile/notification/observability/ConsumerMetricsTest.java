package dev.dogukankat.reconcile.notification.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConsumerMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ConsumerMetrics metrics = new ConsumerMetrics(registry);

    @Test
    void dlqCounterTaggedByTopicAndExceptionClass() {
        metrics.recordDlq("reconcile.authorization.v1",
                "dev.dogukankat.reconcile.notification.error.NonRetryableConsumerException");
        metrics.recordDlq("reconcile.authorization.v1",
                "dev.dogukankat.reconcile.notification.error.NonRetryableConsumerException");
        metrics.recordDlq("reconcile.authorization.v1",
                "dev.dogukankat.reconcile.notification.error.RetryableConsumerException");

        double nonRetryable = registry.counter("reconcile_consumer_dlq_total",
                "topic", "reconcile.authorization.v1",
                "exception_class",
                "dev.dogukankat.reconcile.notification.error.NonRetryableConsumerException")
                .count();
        double retryable = registry.counter("reconcile_consumer_dlq_total",
                "topic", "reconcile.authorization.v1",
                "exception_class",
                "dev.dogukankat.reconcile.notification.error.RetryableConsumerException")
                .count();

        assertThat(nonRetryable).isEqualTo(2.0);
        assertThat(retryable).isEqualTo(1.0);
    }
}
