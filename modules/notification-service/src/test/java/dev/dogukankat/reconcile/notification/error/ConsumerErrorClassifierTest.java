package dev.dogukankat.reconcile.notification.error;

import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.DeserializationException;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsumerErrorClassifierTest {

    private final ConsumerErrorClassifier classifier = new ConsumerErrorClassifier();

    @Test
    void retryableMarkerClassifiesAsRetryable() {
        RetryableConsumerException e = new RetryableConsumerException(
                "downstream 503", new RuntimeException());

        assertThat(classifier.classify(e)).isEqualTo(Classification.RETRYABLE);
    }

    @Test
    void nonRetryableMarkerClassifiesAsNonRetryable() {
        NonRetryableConsumerException e = new NonRetryableConsumerException(
                "malformed header", new IllegalArgumentException());

        assertThat(classifier.classify(e)).isEqualTo(Classification.NON_RETRYABLE);
    }

    @Test
    void deserializationExceptionClassifiesAsNonRetryable() {
        DeserializationException e = new DeserializationException(
                "bad payload", "raw-bytes".getBytes(),
                false, new SerializationException("bad bytes"));

        assertThat(classifier.classify(e)).isEqualTo(Classification.NON_RETRYABLE);
    }

    @Test
    void socketTimeoutBareClassifiesAsRetryable() {
        SocketTimeoutException timeout = new SocketTimeoutException("read timed out");

        assertThat(classifier.classify(timeout)).isEqualTo(Classification.RETRYABLE);
    }

    @Test
    void socketTimeoutWrappedInRuntimeExceptionClassifiesAsRetryable() {
        RuntimeException wrapped = new RuntimeException(
                "consumer poll failed",
                new RuntimeException(new SocketTimeoutException("inner")));

        assertThat(classifier.classify(wrapped)).isEqualTo(Classification.RETRYABLE);
    }

    @Test
    void unknownRuntimeExceptionFallsBackToNonRetryable() {
        RuntimeException unknown = new RuntimeException("who knows");

        assertThat(classifier.classify(unknown)).isEqualTo(Classification.NON_RETRYABLE);
    }

    @Test
    void illegalStateExceptionFromBusinessLogicIsNonRetryable() {
        IllegalStateException invariant = new IllegalStateException(
                "aggregate already terminal");

        assertThat(classifier.classify(invariant)).isEqualTo(Classification.NON_RETRYABLE);
    }

    @Test
    void retryableTakesPrecedenceOverNonRetryableInCauseChain() {
        // If the immediate exception is explicitly retryable, that wins over
        // a non-retryable cause buried below it. The thrower made an
        // explicit claim and we honor it.
        RetryableConsumerException retryable = new RetryableConsumerException(
                "wrapped for retry", new DeserializationException(
                        "would have been non-retryable",
                        new byte[0], false, new SerializationException("inner")));

        assertThat(classifier.classify(retryable)).isEqualTo(Classification.RETRYABLE);
    }

    @Test
    void nullThrowableThrowsNullPointerException() {
        assertThatThrownBy(() -> classifier.classify(null))
                .isInstanceOf(NullPointerException.class);
    }
}
