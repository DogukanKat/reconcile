package dev.dogukankat.reconcile.notification.dlt;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport;
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Subclasses {@link RetryTopicConfigurationSupport} so the
 * configure-customizers lifecycle hook fires. Plugs a
 * {@link TruncatingExceptionHeadersCreator} into the
 * {@code DeadLetterPublishingRecoverer} Spring Kafka constructs
 * internally for the retry-topic chain. Without this override, deep
 * Java stack traces blow past Kafka's default {@code message.max.bytes}.
 *
 * {@code @EnableKafka} replaces {@code @EnableKafkaRetryTopic} when you
 * provide your own support subclass — the annotation imports a default
 * support bean that would conflict with this one. Documented in
 * Spring Kafka 3.x reference; the comment is here because losing
 * an afternoon to a duplicate-bean failure is the kind of trap I'd
 * rather mark in the code.
 */
@Configuration
@EnableKafka
public class DltConfig extends RetryTopicConfigurationSupport {

    private static final int STACK_TRACE_MAX_BYTES = 4096;

    @Override
    protected void configureCustomizers(CustomizersConfigurer customizers) {
        customizers.customizeDeadLetterPublishingRecoverer(dlpr ->
                dlpr.setExceptionHeadersCreator(
                        new TruncatingExceptionHeadersCreator(STACK_TRACE_MAX_BYTES)));
    }

    /**
     * Spring Kafka's auto-config provides a default scheduler when its
     * own {@link RetryTopicConfigurationSupport} is used. With our
     * custom subclass that default doesn't fire, so the back-off
     * manager bean can't construct itself. Two threads is enough for
     * the retry-delay scheduling at our throughput; bump if a future
     * load test says otherwise.
     */
    @Bean
    public RetryTopicSchedulerWrapper retryTopicSchedulerWrapper() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("reconcile-retry-");
        scheduler.initialize();
        return new RetryTopicSchedulerWrapper(scheduler);
    }
}
