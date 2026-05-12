package dev.dogukankat.reconcile.payment.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyHashingTest {

    private final IdempotencyHashing hashing = new IdempotencyHashing();

    @Test
    void sameRequestProducesSameHash() {
        AuthorizeRequest a = sample();
        AuthorizeRequest b = sample();

        assertThat(hashing.compute(a)).isEqualTo(hashing.compute(b));
    }

    @Test
    void differentAmountChangesHash() {
        AuthorizeRequest a = new AuthorizeRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "100.00", "USD",
                Instant.parse("2026-05-19T10:00:00Z"));
        AuthorizeRequest b = new AuthorizeRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "200.00", "USD",
                Instant.parse("2026-05-19T10:00:00Z"));

        assertThat(hashing.compute(a)).isNotEqualTo(hashing.compute(b));
    }

    @Test
    void hashIs64HexChars() {
        String hash = hashing.compute(sample());

        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }

    private static AuthorizeRequest sample() {
        return new AuthorizeRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "100.00",
                "USD",
                Instant.parse("2026-05-19T10:00:00Z"));
    }
}
