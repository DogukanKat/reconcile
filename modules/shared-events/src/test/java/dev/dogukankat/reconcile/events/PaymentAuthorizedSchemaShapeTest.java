package dev.dogukankat.reconcile.events;

import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the v1 contract. These assertions are the baseline Feature
 * 05's evolution tests reason against: v1 has exactly these five
 * fields, all required (no defaults), and the decimal precision/scale
 * is fixed. v1 carries no speculative optional fields on purpose —
 * Feature 05 introduces an optional field and then removes it within
 * its own test schemas (add-then-remove) rather than v1 shipping a
 * field it doesn't need just to enable a later test. Changing
 * anything asserted here is a wire-breaking change and should fail
 * loudly in review, not slip through.
 */
class PaymentAuthorizedSchemaShapeTest {

    private final Schema schema = PaymentAuthorized.getClassSchema();

    @Test
    void hasExactlyTheFiveV1FieldsInOrder() {
        assertThat(schema.getFields())
                .extracting(Schema.Field::name)
                .containsExactly(
                        "authorizationId", "merchantId", "amount",
                        "currency", "occurredAt");
    }

    @Test
    void noFieldHasADefaultInV1() {
        // A field with a default in v1 would be a quiet invitation to
        // treat it as optional. v1 is all-required by design; the
        // evolution story starts in Feature 05, not here.
        assertThat(schema.getFields())
                .allSatisfy(f -> assertThat(f.hasDefaultValue())
                        .as("field %s must not have a default in v1", f.name())
                        .isFalse());
    }

    @Test
    void amountIsDecimalWithScale4Precision18() {
        Schema amount = schema.getField("amount").schema();
        LogicalType lt = amount.getLogicalType();

        assertThat(amount.getType()).isEqualTo(Schema.Type.BYTES);
        assertThat(lt).isInstanceOf(LogicalTypes.Decimal.class);
        LogicalTypes.Decimal decimal = (LogicalTypes.Decimal) lt;
        assertThat(decimal.getScale()).isEqualTo(4);
        assertThat(decimal.getPrecision()).isEqualTo(18);
    }

    @Test
    void occurredAtIsTimestampMicros() {
        Schema occurredAt = schema.getField("occurredAt").schema();

        assertThat(occurredAt.getType()).isEqualTo(Schema.Type.LONG);
        assertThat(occurredAt.getLogicalType())
                .isInstanceOf(LogicalTypes.TimestampMicros.class);
    }

    @Test
    void idsAndCurrencyAreStrings() {
        List<String> stringFields =
                List.of("authorizationId", "merchantId", "currency");
        assertThat(stringFields).allSatisfy(name ->
                assertThat(schema.getField(name).schema().getType())
                        .as("%s should be a plain string", name)
                        .isEqualTo(Schema.Type.STRING));
    }

    @Test
    void namespaceFollowsTheRepoConvention() {
        assertThat(schema.getNamespace())
                .isEqualTo("dev.dogukankat.reconcile.events");
        assertThat(schema.getName()).isEqualTo("PaymentAuthorized");
    }
}
