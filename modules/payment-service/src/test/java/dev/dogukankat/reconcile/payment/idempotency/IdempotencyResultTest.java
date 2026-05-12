package dev.dogukankat.reconcile.payment.idempotency;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyResultTest {

    @Test
    void completedCarriesCachedResponseFields() {
        UUID id = UUID.randomUUID();
        IdempotencyResult.Completed c = new IdempotencyResult.Completed(
                201, "{\"id\":\"...\"}", id);

        assertThat(c.responseStatus()).isEqualTo(201);
        assertThat(c.responseBody()).contains("\"id\"");
        assertThat(c.resourceId()).isEqualTo(id);
    }

    @Test
    void sealedHierarchyHasFourPermittedShapes() {
        // Exhaustiveness is enforced by the compiler when callers switch
        // on IdempotencyResult; this test just pins the four shapes so a
        // future addition is a conscious choice.
        assertThat(IdempotencyResult.class.getPermittedSubclasses())
                .extracting(Class::getSimpleName)
                .containsExactlyInAnyOrder(
                        "Inserted", "InProgress", "HashMismatch", "Completed");
    }
}
