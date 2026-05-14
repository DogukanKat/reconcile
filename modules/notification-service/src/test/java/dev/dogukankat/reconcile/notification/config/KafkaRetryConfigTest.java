package dev.dogukankat.reconcile.notification.config;

import dev.dogukankat.reconcile.notification.error.NonRetryableConsumerException;
import dev.dogukankat.reconcile.notification.error.RetryableConsumerException;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.retrytopic.DestinationTopic;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaRetryConfigTest {

    private static final RetryProperties PROPS = new RetryProperties(
            Duration.ofSeconds(1),
            3.0,
            Duration.ofMinutes(5),
            4);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);

    @Test
    void retryChainHasOneMainPlusThreeRetryEntriesPerExponentialBackoff() {
        RetryTopicConfiguration config = new KafkaRetryConfig()
                .authorizationRetryTopicConfiguration(template, PROPS);

        List<DestinationTopic.Properties> destinations = config.getDestinationTopicProperties();
        // maxAttempts=4 means main + 3 retries; the DLT entry brings total to 5.
        assertThat(destinations)
                .as("main + retry.0 + retry.1 + retry.2 + DLT")
                .hasSize(5);
    }

    @Test
    void retryDelaysFollowExponentialBackoff() {
        RetryTopicConfiguration config = new KafkaRetryConfig()
                .authorizationRetryTopicConfiguration(template, PROPS);

        List<Long> delays = config.getDestinationTopicProperties().stream()
                .map(DestinationTopic.Properties::delay)
                .toList();
        // Main topic delay 0; retries 1s, 3s, 9s; DLT 0.
        assertThat(delays).containsExactly(0L, 1_000L, 3_000L, 9_000L, 0L);
    }

    @Test
    void retryTopicNamingSuffixesWithRetryAttemptIndex() {
        RetryTopicConfiguration config = new KafkaRetryConfig()
                .authorizationRetryTopicConfiguration(template, PROPS);

        List<String> suffixes = config.getDestinationTopicProperties().stream()
                .map(DestinationTopic.Properties::suffix)
                .toList();
        assertThat(suffixes).containsExactly(
                "",
                "-retry-0",
                "-retry-1",
                "-retry-2",
                "-dlt");
    }

    @Test
    void retryableConsumerExceptionIsOnTheRetryList() {
        DestinationTopic main = mainTopicFrom(buildConfig());

        boolean shouldRetry = main.shouldRetryOn(0,
                new RetryableConsumerException("downstream 503", new RuntimeException()));

        assertThat(shouldRetry).isTrue();
    }

    @Test
    void socketTimeoutWrappedInCauseChainIsOnTheRetryList() {
        DestinationTopic main = mainTopicFrom(buildConfig());

        RuntimeException wrapped = new RuntimeException(
                "poll failed",
                new RuntimeException(new SocketTimeoutException("inner")));

        assertThat(main.shouldRetryOn(0, wrapped)).isTrue();
    }

    @Test
    void nonRetryableConsumerExceptionShortCircuitsToDlt() {
        DestinationTopic main = mainTopicFrom(buildConfig());

        boolean shouldRetry = main.shouldRetryOn(0,
                new NonRetryableConsumerException("malformed payload"));

        assertThat(shouldRetry).isFalse();
    }

    @Test
    void unknownRuntimeExceptionFallsThroughToNonRetryable() {
        DestinationTopic main = mainTopicFrom(buildConfig());

        assertThat(main.shouldRetryOn(0, new RuntimeException("who knows")))
                .isFalse();
    }

    private RetryTopicConfiguration buildConfig() {
        return new KafkaRetryConfig().authorizationRetryTopicConfiguration(template, PROPS);
    }

    private static DestinationTopic mainTopicFrom(RetryTopicConfiguration config) {
        return new DestinationTopic(
                "reconcile.authorization.v1",
                config.getDestinationTopicProperties().get(0));
    }
}
