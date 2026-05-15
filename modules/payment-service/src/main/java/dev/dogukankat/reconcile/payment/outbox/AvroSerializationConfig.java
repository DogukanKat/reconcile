package dev.dogukankat.reconcile.payment.outbox;

import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Builds the registry-aware Avro value serializer the outbox writer
 * uses. The registry URL is config so the IT can point it at the
 * {@code make up} Schema Registry (host 8085) via
 * {@code @DynamicPropertySource}, and a future containerised deploy
 * can point it at {@code http://schema-registry:8081} inside the
 * docker network without a code change.
 *
 * {@code auto.register.schemas} stays on (the default): the first
 * {@code PaymentAuthorized} publish registers the {@code shared-events}
 * schema under {@code reconcile.authorization.v1-value}. That's the
 * point of Option C — the application is the schema author, so the
 * registered schema is the contract by construction, not an inferred
 * approximation.
 */
@Configuration
public class AvroSerializationConfig {

    @Bean
    KafkaAvroSerializer outboxAvroSerializer(
            @Value("${reconcile.schema-registry.url}") String schemaRegistryUrl) {
        KafkaAvroSerializer serializer = new KafkaAvroSerializer();
        // isKey = false: this is a value serializer, so the default
        // TopicNameStrategy yields subject "<topic>-value".
        serializer.configure(
                Map.of(KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG,
                        schemaRegistryUrl),
                false);
        return serializer;
    }
}
