package dev.dogukankat.reconcile.notification.config;

import dev.dogukankat.reconcile.notification.error.RetryableConsumerException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder;

import java.net.SocketTimeoutException;
import java.util.List;

/**
 * Wires Spring Kafka's retry-topic chain in front of every listener
 * subscribed to {@code reconcile.authorization.v1}. The chain is
 * scoped to that topic explicitly because adding a second consumer
 * later (e.g. a webhook-dispatch service on a different topic) should
 * be a deliberate decision, not an accident of bean scoping.
 *
 * The {@code retryOn} list is the only thing the retry decision walks:
 *
 * - {@link RetryableConsumerException} — caller's explicit claim.
 * - {@link SocketTimeoutException} anywhere in the cause chain
 *   (because {@code traversingCauses()} is on) — network glitches
 *   almost always recover after a backoff.
 *
 * Anything else, including unknown {@link RuntimeException} and our
 * own {@code NonRetryableConsumerException}, falls through to the DLT
 * on the first attempt. That mirrors the conservative default in
 * Feature 02's {@code ConsumerErrorClassifier} and is the most
 * important property of the chain: retry-by-default is how poison
 * pills become incidents.
 */
@Configuration
public class KafkaRetryConfig {

    @Bean
    public RetryTopicConfiguration authorizationRetryTopicConfiguration(
            KafkaOperations<String, String> template,
            RetryProperties props) {
        return RetryTopicConfigurationBuilder.newInstance()
                .maxAttempts(props.maxAttempts())
                .exponentialBackoff(
                        props.initialDelay().toMillis(),
                        props.multiplier(),
                        props.maxDelay().toMillis())
                .retryOn(List.of(
                        RetryableConsumerException.class,
                        SocketTimeoutException.class))
                .traversingCauses()
                // Default suffix is delay-based (-retry-1000, -retry-3000)
                // which is unreadable when the backoff changes. Index-based
                // gives -retry-0, -retry-1, -retry-2 and the meaning is
                // obvious without consulting the config.
                .suffixTopicsWithIndexValues()
                // Spring Kafka's @DltHandler isn't auto-discovered with the
                // RetryTopicConfiguration bean approach — without this
                // explicit registration, the default "log only" handler
                // takes over and AuthorizationEventListener.onDlt never
                // fires. The reference (bean name, method) wires the
                // method back into the retry-topic infrastructure.
                .dltHandlerMethod("authorizationEventListener", "onDlt")
                .includeTopic("reconcile.authorization.v1")
                .create(template);
    }
}
