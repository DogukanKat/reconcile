package dev.dogukankat.reconcile.payment.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import dev.dogukankat.reconcile.payment.architecture.fixture.BadController;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the architectural promise of ADR-0006: every mutating endpoint
 * goes through the idempotency path. Concretely, every method
 * annotated {@code @PostMapping} on a {@code @RestController} class
 * must declare an {@code @RequestHeader("Idempotency-Key")} parameter.
 * Without this guardrail a future controller author could silently
 * skip the idempotency reserve/complete flow and we wouldn't notice
 * until the first duplicate charge in production.
 *
 * The negative test imports a hand-rolled fixture that violates the
 * rule, so we know the rule catches what it's meant to catch and not
 * just whatever happens to be lying around in the production tree.
 */
class ArchitectureTest {

    private static final String HEADER_NAME = "Idempotency-Key";

    private static final ArchRule postMappingsRequireIdempotencyHeader = methods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .and().areAnnotatedWith(PostMapping.class)
            .should(haveIdempotencyKeyHeaderParameter());

    @Test
    void productionControllersAllAcceptTheIdempotencyKeyHeader() {
        JavaClasses production = new ClassFileImporter()
                .importPackages("dev.dogukankat.reconcile.payment.api");

        postMappingsRequireIdempotencyHeader.check(production);
    }

    @Test
    void ruleFailsOnAPostMappingThatLacksTheHeader() {
        JavaClasses fixture = new ClassFileImporter().importClasses(BadController.class);

        assertThatThrownBy(() -> postMappingsRequireIdempotencyHeader.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(HEADER_NAME);
    }

    private static ArchCondition<JavaMethod> haveIdempotencyKeyHeaderParameter() {
        return new ArchCondition<>("declare an @RequestHeader(\"" + HEADER_NAME + "\") parameter") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean ok = method.getParameters().stream()
                        .anyMatch(ArchitectureTest::isIdempotencyKeyHeader);
                if (!ok) {
                    events.add(SimpleConditionEvent.violated(
                            method,
                            "method "
                                    + method.getFullName()
                                    + " is @PostMapping but does not declare "
                                    + "an @RequestHeader(\"" + HEADER_NAME + "\") parameter"));
                }
            }
        };
    }

    private static boolean isIdempotencyKeyHeader(JavaParameter parameter) {
        if (!parameter.isAnnotatedWith(RequestHeader.class)) {
            return false;
        }
        RequestHeader header = parameter.getAnnotationOfType(RequestHeader.class);
        return HEADER_NAME.equals(header.value()) || HEADER_NAME.equals(header.name());
    }
}
