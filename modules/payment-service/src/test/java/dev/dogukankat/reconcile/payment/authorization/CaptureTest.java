package dev.dogukankat.reconcile.payment.authorization;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaptureTest {

    @Test
    void rejectsNullFields() {
        assertThatThrownBy(() -> new Capture(
                null,
                new Money(BigDecimal.ONE, "USD"),
                CaptureStatus.PENDING,
                Instant.now(),
                null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void completedAtMayBeNullForPending() {
        Capture c = new Capture(
                CaptureId.generate(),
                new Money(BigDecimal.ONE, "USD"),
                CaptureStatus.PENDING,
                Instant.now(),
                null);
        assertThat(c.completedAt()).isNull();
        assertThat(c.status()).isEqualTo(CaptureStatus.PENDING);
    }
}
