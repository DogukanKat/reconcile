package dev.dogukankat.reconcile.payment.api;

import dev.dogukankat.reconcile.payment.outbox.OutboxEntry;
import dev.dogukankat.reconcile.payment.outbox.OutboxRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end check that a correlation ID set on the inbound HTTP
 * request lands on the outbox row Debezium will project. The whole
 * point of Feature 01 is this chain working without a special path —
 * the controller, service, and writer don't take a correlationId
 * parameter; the value travels through MDC. This test would catch
 * any regression that breaks that handoff.
 *
 * Runs against the local Postgres that {@code make up} starts (same
 * pattern as the other ITs while the Testcontainers / Docker Desktop
 * version negotiation is unresolved).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class CorrelationIdEndToEndIT {

    @Autowired MockMvc mockMvc;
    @Autowired OutboxRepository outbox;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void wipe() {
        jdbc.sql("DELETE FROM refunds").update();
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM idempotency_keys").update();
        jdbc.sql("DELETE FROM authorizations").update();
    }

    @Test
    void inboundCorrelationIdLandsOnOutboxRow() throws Exception {
        String body = """
                {
                  "merchantId": "00000000-0000-0000-0000-000000000001",
                  "amount": "42.00",
                  "currency": "USD",
                  "expiresAt": "2026-05-20T10:00:00Z"
                }
                """;

        MvcResult result = mockMvc.perform(post("/authorizations")
                        .header("X-Correlation-Id", "corr-e2e-1")
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        UUID aggregateId = UUID.fromString(
                result.getResponse().getHeader("Location").replace("/authorizations/", ""));
        List<OutboxEntry> events = outbox.findByAggregateId(aggregateId);
        assertThat(events).isNotEmpty();
        assertThat(events).allSatisfy(e ->
                assertThat(e.correlationId()).isEqualTo("corr-e2e-1"));
        assertThat(result.getResponse().getHeader("X-Correlation-Id"))
                .isEqualTo("corr-e2e-1");
    }

    @Test
    void missingCorrelationIdHeaderGeneratesOneAndStampsItOnOutbox() throws Exception {
        String body = """
                {
                  "merchantId": "00000000-0000-0000-0000-000000000002",
                  "amount": "99.99",
                  "currency": "EUR",
                  "expiresAt": "2026-05-20T10:00:00Z"
                }
                """;

        MvcResult result = mockMvc.perform(post("/authorizations")
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        String echoed = result.getResponse().getHeader("X-Correlation-Id");
        assertThat(echoed).isNotNull();
        UUID.fromString(echoed); // valid UUID

        UUID aggregateId = UUID.fromString(
                result.getResponse().getHeader("Location").replace("/authorizations/", ""));
        List<OutboxEntry> events = outbox.findByAggregateId(aggregateId);
        assertThat(events).isNotEmpty();
        assertThat(events).allSatisfy(e ->
                assertThat(e.correlationId()).isEqualTo(echoed));
    }
}
