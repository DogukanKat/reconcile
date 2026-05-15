package dev.dogukankat.reconcile.payment.outbox;

import dev.dogukankat.reconcile.events.PaymentAuthorized;

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Phase 3 payoff proof: a real POST /authorizations travels
 * through the outbox, Debezium CDC, the EventRouter SMT, and lands
 * on reconcile.authorization.v1 as Confluent-framed Avro whose
 * schema — resolved from the real Schema Registry by the id in the
 * message — is the shared-events contract, not something Debezium
 * inferred.
 *
 * Runs against the make up stack (Postgres, Kafka, Debezium with
 * the ByteArrayConverter connector, Schema Registry). Same
 * local-infra pattern as the Phase 2 PoisonMessageIT; the Docker
 * Desktop / Testcontainers issue is still open.
 *
 * Precondition: the connector must be re-registered with the
 * Phase 3 config (make register-connector). The PR documents that
 * operational step, the same way Phase 2 Feature 01 did.
 *
 * The consumer reads raw bytes and Avro-decodes only the record
 * whose key matches this test's authorization. The topic still
 * carries historical Phase 1/2 JSON messages and the not-yet-Avro
 * event types; an Avro deserializer pointed at the whole stream
 * would choke on the first non-Avro record. Filter first, decode
 * the one that's ours.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AvroProducerEndToEndIT {

    private static final String TOPIC = "reconcile.authorization.v1";
    private static final String BOOTSTRAP = "localhost:9094";
    private static final String REGISTRY = "http://localhost:8085";
    private static final Duration MAX_WAIT = Duration.ofSeconds(25);

    @Autowired
    MockMvc mockMvc;

    @Test
    void postAuthorizationLandsAsRegisteredAvroOnTheTopic() throws Exception {
        try (KafkaConsumer<String, byte[]> consumer = rawConsumer()) {
            consumer.subscribe(List.of(TOPIC));
            // A single poll doesn't guarantee the group join completed;
            // with auto.offset.reset=latest a message published before
            // assignment finishes is skipped. Poll until the consumer
            // actually owns a partition, then publish.
            await(() -> {
                consumer.poll(Duration.ofMillis(200));
                return !consumer.assignment().isEmpty();
            });

            String merchantId = UUID.randomUUID().toString();
            String body = """
                    {
                      "merchantId": "%s",
                      "amount": "137.5500",
                      "currency": "EUR",
                      "expiresAt": "2026-06-01T10:00:00Z"
                    }
                    """.formatted(merchantId);

            MvcResult result = mockMvc.perform(post("/authorizations")
                            .header("Idempotency-Key", "p3-it-" + UUID.randomUUID())
                            .header("X-Correlation-Id", "p3-e2e")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andReturn();

            String authId = UUID.fromString(result.getResponse()
                    .getHeader("Location").replace("/authorizations/", "")).toString();

            byte[] avroBytes = pollForKey(consumer, authId);
            assertThat(avroBytes)
                    .as("an Avro message for %s should reach %s via Debezium", authId, TOPIC)
                    .isNotNull();
            // Confluent wire framing, end to end (not just in the unit test).
            assertThat(avroBytes[0]).as("Confluent magic byte").isZero();

            PaymentAuthorized decoded = decode(avroBytes);
            assertThat(decoded.getAuthorizationId()).isEqualTo(authId);
            assertThat(decoded.getMerchantId()).isEqualTo(merchantId);
            assertThat(decoded.getAmount()).isEqualByComparingTo("137.5500");
            assertThat(decoded.getAmount().scale()).isEqualTo(4);
            assertThat(decoded.getCurrency()).isEqualTo("EUR");
        }
    }

    @Test
    void registeredSchemaEqualsTheSharedEventsContract() throws Exception {
        // Drive a publish so the subject exists, then assert the
        // registered schema is byte-equivalent to the generated
        // shared-events schema — i.e. Option C delivered the real
        // contract, not an inferred approximation (Option A's failure
        // mode, the reason A was rejected in ADR-0009).
        mockMvc.perform(post("/authorizations")
                        .header("Idempotency-Key", "p3-it-" + UUID.randomUUID())
                        .header("X-Correlation-Id", "p3-e2e-schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchantId": "%s",
                                  "amount": "1.0000",
                                  "currency": "USD",
                                  "expiresAt": "2026-06-01T10:00:00Z"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated());

        SchemaRegistryClient client =
                new CachedSchemaRegistryClient(REGISTRY, 10);
        await(() -> client.getAllSubjects().contains(TOPIC + "-value"));

        String registered = client.getLatestSchemaMetadata(TOPIC + "-value").getSchema();
        String contract = PaymentAuthorized.getClassSchema().toString();
        assertThat(new org.apache.avro.Schema.Parser().parse(registered))
                .isEqualTo(new org.apache.avro.Schema.Parser().parse(contract));
    }

    private static KafkaConsumer<String, byte[]> rawConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "p3-e2e-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    private static byte[] pollForKey(KafkaConsumer<String, byte[]> consumer, String key) {
        long deadline = System.nanoTime() + MAX_WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, byte[]> records =
                    consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, byte[]> record : records) {
                if (key.equals(record.key())) {
                    return record.value();
                }
            }
        }
        return null;
    }

    private static PaymentAuthorized decode(byte[] confluentFramed) {
        try (KafkaAvroDeserializer deserializer = new KafkaAvroDeserializer()) {
            deserializer.configure(Map.of(
                    "schema.registry.url", REGISTRY,
                    "specific.avro.reader", true), false);
            return (PaymentAuthorized) deserializer.deserialize(TOPIC, confluentFramed);
        }
    }

    private static void await(ThrowingCondition condition) {
        long deadline = System.nanoTime() + MAX_WAIT.toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                if (condition.test()) {
                    return;
                }
            } catch (Exception e) {
                last = new IllegalStateException(e);
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ie);
            }
        }
        throw new AssertionError("condition not met within " + MAX_WAIT, last);
    }

    @FunctionalInterface
    private interface ThrowingCondition {
        boolean test() throws Exception;
    }
}
