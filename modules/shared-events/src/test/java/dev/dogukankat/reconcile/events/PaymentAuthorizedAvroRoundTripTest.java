package dev.dogukankat.reconcile.events;

import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binary round trip with no registry. Proves the two logical types
 * that matter survive serialization with full fidelity: the
 * {@code decimal} amount keeps its scale (a float would not), and
 * {@code timestamp-micros} keeps microsecond precision (a
 * {@code timestamp-millis} would silently truncate). Everything
 * downstream in Phase 3 assumes this; assuming it without a test is
 * exactly the schema-drift class of bug the phase exists to prevent.
 */
class PaymentAuthorizedAvroRoundTripTest {

    @Test
    void roundTripsThroughBinaryPreservingDecimalScaleAndMicroTimestamp() throws Exception {
        BigDecimal amount = new BigDecimal("1234.5600"); // scale 4, trailing zeros significant
        Instant occurredAt = Instant.parse("2026-05-15T11:42:25.123456Z"); // microsecond precision

        PaymentAuthorized original = PaymentAuthorized.newBuilder()
                .setAuthorizationId(UUID.randomUUID().toString())
                .setMerchantId(UUID.randomUUID().toString())
                .setAmount(amount)
                .setCurrency("USD")
                .setOccurredAt(occurredAt)
                .build();

        PaymentAuthorized roundTripped = serializeThenDeserialize(original);

        assertThat(roundTripped.getAuthorizationId()).isEqualTo(original.getAuthorizationId());
        assertThat(roundTripped.getMerchantId()).isEqualTo(original.getMerchantId());
        assertThat(roundTripped.getCurrency()).isEqualTo("USD");

        // Scale must survive — 1234.5600, not 1234.56. Money.SCALE is 4
        // and the wire has to carry that, not normalize it away.
        assertThat(roundTripped.getAmount()).isEqualByComparingTo(amount);
        assertThat(roundTripped.getAmount().scale()).isEqualTo(4);

        // Microsecond precision must survive. timestamp-millis would
        // drop the .000456 and this assertion would fail.
        assertThat(roundTripped.getOccurredAt()).isEqualTo(occurredAt);
        assertThat(roundTripped.getOccurredAt())
                .isEqualTo(occurredAt.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void zeroAmountRoundTripsWithScalePreserved() throws Exception {
        PaymentAuthorized original = PaymentAuthorized.newBuilder()
                .setAuthorizationId(UUID.randomUUID().toString())
                .setMerchantId(UUID.randomUUID().toString())
                .setAmount(new BigDecimal("0.0000"))
                .setCurrency("EUR")
                .setOccurredAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();

        PaymentAuthorized roundTripped = serializeThenDeserialize(original);

        assertThat(roundTripped.getAmount()).isEqualByComparingTo("0.0000");
        assertThat(roundTripped.getAmount().scale()).isEqualTo(4);
    }

    private static PaymentAuthorized serializeThenDeserialize(PaymentAuthorized record)
            throws Exception {
        SpecificDatumWriter<PaymentAuthorized> writer =
                new SpecificDatumWriter<>(PaymentAuthorized.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
        writer.write(record, encoder);
        encoder.flush();

        SpecificDatumReader<PaymentAuthorized> reader =
                new SpecificDatumReader<>(PaymentAuthorized.class);
        BinaryDecoder decoder =
                DecoderFactory.get().binaryDecoder(out.toByteArray(), null);
        return reader.read(null, decoder);
    }
}
