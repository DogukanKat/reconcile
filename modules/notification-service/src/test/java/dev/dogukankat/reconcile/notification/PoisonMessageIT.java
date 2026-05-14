package dev.dogukankat.reconcile.notification;

import dev.dogukankat.reconcile.notification.error.NonRetryableConsumerException;
import dev.dogukankat.reconcile.notification.error.RetryableConsumerException;
import dev.dogukankat.reconcile.notification.listener.ListenerFaultInjector;
import dev.dogukankat.reconcile.notification.support.ScriptedListenerFaultInjector;
import dev.dogukankat.reconcile.notification.support.ScriptedListenerFaultInjector.Outcome;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the retry → DLT chain against the local Kafka that
 * {@code make up} brings up. Same shape as payment-service's
 * {@code @SpringBootTest} integration pattern (commit
 * {@code cd4a681}) — Testcontainers + Docker Desktop 4.72
 * version-negotiation issue is still open
 * ({@code docs/notes-from-the-build.md}, 2026-05-12).
 *
 * Each scenario uses a fresh UUID as the record key so concurrent
 * Debezium events on the same topic don't poison the assertions.
 * The scripted injector keys behaviour by record key — anything not
 * scripted passes through with no fault.
 */
@SpringBootTest
@ActiveProfiles("it")
class PoisonMessageIT {

    private static final String SOURCE_TOPIC = "reconcile.authorization.v1";
    // Pinned at class load so the property resolves to the same value across
    // every container (Spring's ${random.uuid} resolves freshly per access,
    // which split the main and retry containers into different groups in
    // an earlier iteration of this test).
    private static final String IT_GROUP =
            "notification-service-it-" + UUID.randomUUID();

    @DynamicPropertySource
    static void configureConsumerGroup(DynamicPropertyRegistry registry) {
        registry.add("reconcile.notification.consumer.group", () -> IT_GROUP);
    }
    private static final String DLT_TOPIC = SOURCE_TOPIC + "-dlt";
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);
    private static final Duration MAX_WAIT = Duration.ofSeconds(8);
    private static final String BOOTSTRAP_SERVERS = "localhost:9094";

    @Autowired KafkaTemplate<String, String> producer;
    @Autowired ScriptedListenerFaultInjector injector;
    @Autowired KafkaListenerEndpointRegistry listenerRegistry;

    @BeforeEach
    void cleanInjector() {
        injector.reset();
        // Listener container start is async; partition assignment can lag.
        // Without this wait a message sent at t=0 misses the assignment
        // window and the listener never sees it (auto-offset-reset=latest).
        // Wait for the source-topic container to have at least one
        // partition assigned. Retry/DLT containers may assign later.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .until(() -> findSourceContainer()
                        .map(MessageListenerContainer::getAssignedPartitions)
                        .filter(p -> !p.isEmpty())
                        .isPresent());
    }

    private java.util.Optional<MessageListenerContainer> findSourceContainer() {
        for (MessageListenerContainer c : listenerRegistry.getListenerContainers()) {
            String[] topics = c.getContainerProperties().getTopics();
            if (topics == null) {
                continue;
            }
            for (String t : topics) {
                if (SOURCE_TOPIC.equals(t)) {
                    return java.util.Optional.of(c);
                }
            }
        }
        return java.util.Optional.empty();
    }

    @AfterEach
    void cleanAgain() {
        injector.reset();
    }

    @Test
    void retryableThenEventualSuccess_messageNeverReachesDlt() {
        String key = uniqueKey();
        injector.scriptFor(key, List.of(Outcome.RETRYABLE, Outcome.RETRYABLE));

        try (KafkaConsumer<String, String> dltConsumer = consumerFor(DLT_TOPIC)) {
            send(key, "{\"scenario\":\"eventual-success\"}");

            await().atMost(MAX_WAIT).pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() ->
                            assertThat(injector.attemptsFor(key))
                                    .as("attempts so far for key %s", key)
                                    .isGreaterThanOrEqualTo(3));

            ConsumerRecord<String, String> dltRecord = pollFor(dltConsumer, key);
            assertThat(dltRecord)
                    .as("no DLT record should have been published for key %s", key)
                    .isNull();
        }
    }

    @Test
    void retryableEveryAttempt_messageLandsOnDltWithFailureHeaders() {
        String key = uniqueKey();
        injector.scriptFor(key, List.of(
                Outcome.RETRYABLE, Outcome.RETRYABLE,
                Outcome.RETRYABLE, Outcome.RETRYABLE));

        try (KafkaConsumer<String, String> dltConsumer = consumerFor(DLT_TOPIC)) {
            send(key, "{\"scenario\":\"retry-exhaustion\"}",
                    Map.of("correlationId", "corr-it-retry"));

            ConsumerRecord<String, String> dltRecord = pollUntilFound(dltConsumer, key);
            assertThat(dltRecord).isNotNull();
            assertThat(injector.attemptsFor(key)).isEqualTo(4);

            assertThat(headerString(dltRecord, KafkaHeaders.DLT_EXCEPTION_FQCN))
                    .isEqualTo(RetryableConsumerException.class.getName());
            assertThat(headerString(dltRecord, KafkaHeaders.ORIGINAL_TOPIC))
                    .isEqualTo(SOURCE_TOPIC);
            assertThat(headerString(dltRecord, "correlationId"))
                    .isEqualTo("corr-it-retry");
        }
    }

    @Test
    void nonRetryableOnFirstAttempt_messageGoesStraightToDlt() {
        String key = uniqueKey();
        injector.scriptFor(key, List.of(Outcome.NON_RETRYABLE));

        try (KafkaConsumer<String, String> dltConsumer = consumerFor(DLT_TOPIC)) {
            send(key, "{\"scenario\":\"immediate-dlt\"}",
                    Map.of("correlationId", "corr-it-nonretry"));

            ConsumerRecord<String, String> dltRecord = pollUntilFound(dltConsumer, key);
            assertThat(dltRecord).isNotNull();
            // First attempt failed → on DLT. No retry topic hops.
            assertThat(injector.attemptsFor(key))
                    .as("non-retryable should fail exactly once before DLT")
                    .isEqualTo(1);

            assertThat(headerString(dltRecord, KafkaHeaders.DLT_EXCEPTION_FQCN))
                    .isEqualTo(NonRetryableConsumerException.class.getName());
            assertThat(headerString(dltRecord, "correlationId"))
                    .isEqualTo("corr-it-nonretry");
        }
    }

    private void send(String key, String payload) {
        send(key, payload, Map.of());
    }

    private void send(String key, String payload, Map<String, String> headers) {
        var record = new org.apache.kafka.clients.producer.ProducerRecord<>(
                SOURCE_TOPIC, null, key, payload);
        // The listener requires eventType and id headers (Feature 02) or
        // it throws NonRetryableConsumerException before the injector even
        // runs. Provide stub values so the scripted injector is what
        // drives the failure path.
        record.headers().add("eventType",
                "PaymentAuthorized".getBytes(StandardCharsets.UTF_8));
        record.headers().add("id",
                UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        headers.forEach((k, v) ->
                record.headers().add(k, v.getBytes(StandardCharsets.UTF_8)));
        producer.send(record);
    }

    private static String uniqueKey() {
        return "it-" + UUID.randomUUID();
    }

    private static KafkaConsumer<String, String> consumerFor(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG,
                "poison-it-observer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(topic));
        // Force partition assignment so messages published after this
        // call land in the consumer's window.
        consumer.poll(Duration.ofMillis(500));
        return consumer;
    }

    private static ConsumerRecord<String, String> pollFor(
            KafkaConsumer<String, String> consumer, String key) {
        long deadline = System.nanoTime() + MAX_WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);
            for (ConsumerRecord<String, String> record : records) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }
        return null;
    }

    private static ConsumerRecord<String, String> pollUntilFound(
            KafkaConsumer<String, String> consumer, String key) {
        ConsumerRecord<String, String> record = pollFor(consumer, key);
        if (record == null) {
            throw new AssertionError(
                    "no DLT record arrived within "
                            + MAX_WAIT + " for key " + key);
        }
        return record;
    }

    private static String headerString(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        ScriptedListenerFaultInjector scriptedListenerFaultInjector() {
            return new ScriptedListenerFaultInjector();
        }

        @Bean
        @Primary
        ListenerFaultInjector listenerFaultInjector(
                ScriptedListenerFaultInjector scripted) {
            return scripted;
        }
    }

}
