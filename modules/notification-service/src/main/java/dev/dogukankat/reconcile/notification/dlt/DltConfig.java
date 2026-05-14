package dev.dogukankat.reconcile.notification.dlt;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport;

/**
 * Plugs a {@link TruncatingExceptionHeadersCreator} into the
 * {@code DeadLetterPublishingRecoverer} Spring Kafka constructs
 * internally for the retry-topic chain. Without this override, deep
 * Java stack traces blow past Kafka's default {@code message.max.bytes}.
 *
 * The bean factory method must be named so it overrides Spring
 * Kafka's auto-configured default; {@link
 * RetryTopicConfigurationSupport} is the documented extension point
 * for retry-topic customization in 3.x.
 */
@Configuration
public class DltConfig {

    private static final int STACK_TRACE_MAX_BYTES = 4096;

    @Bean
    public RetryTopicConfigurationSupport retryTopicConfigurationSupport() {
        return new RetryTopicConfigurationSupport() {
            @Override
            protected void configureCustomizers(CustomizersConfigurer customizers) {
                customizers.customizeDeadLetterPublishingRecoverer(dlpr -> {
                    dlpr.setExceptionHeadersCreator(
                            new TruncatingExceptionHeadersCreator(STACK_TRACE_MAX_BYTES));
                });
            }
        };
    }
}
