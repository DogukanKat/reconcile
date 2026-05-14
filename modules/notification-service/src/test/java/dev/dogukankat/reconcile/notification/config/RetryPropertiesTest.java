package dev.dogukankat.reconcile.notification.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPropertiesTest {

    @Test
    void defaultValuesMatchAdr0008Draft() {
        RetryProperties props = new RetryProperties(
                Duration.ofSeconds(1),
                3.0,
                Duration.ofMinutes(5),
                4);

        assertThat(props.initialDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(props.multiplier()).isEqualTo(3.0);
        assertThat(props.maxDelay()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.maxAttempts()).isEqualTo(4);
    }

    @Test
    void initialDelayMustBePositive() {
        assertThatThrownBy(() -> new RetryProperties(
                Duration.ZERO, 2.0, Duration.ofMinutes(1), 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initialDelay");
    }

    @Test
    void multiplierMustBeGreaterThanOne() {
        assertThatThrownBy(() -> new RetryProperties(
                Duration.ofSeconds(1), 1.0, Duration.ofMinutes(1), 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiplier");
    }

    @Test
    void maxDelayMustNotBeLessThanInitialDelay() {
        assertThatThrownBy(() -> new RetryProperties(
                Duration.ofSeconds(10), 2.0, Duration.ofSeconds(5), 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDelay");
    }

    @Test
    void maxAttemptsMustBeAtLeastTwo() {
        // One attempt = no retries, defeats the purpose. Reject at construction.
        assertThatThrownBy(() -> new RetryProperties(
                Duration.ofSeconds(1), 2.0, Duration.ofMinutes(1), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
    }
}
