package dev.dogukankat.reconcile.payment.refund;

import dev.dogukankat.reconcile.payment.authorization.Authorization;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationRepository;
import dev.dogukankat.reconcile.payment.authorization.Capture;
import dev.dogukankat.reconcile.payment.authorization.CaptureId;
import dev.dogukankat.reconcile.payment.authorization.CaptureStatus;
import dev.dogukankat.reconcile.payment.authorization.MerchantId;
import dev.dogukankat.reconcile.payment.authorization.Money;
import dev.dogukankat.reconcile.payment.refund.RefundStatus.Pending;
import dev.dogukankat.reconcile.payment.refund.RefundStatus.Succeeded;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test against local Postgres. Sets up a parent
 * authorization + capture row first because refunds.capture_id has an
 * FK to captures.id; without it the inserts would explode.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RefundRepositoryIT {

    @Autowired RefundRepository refunds;
    @Autowired AuthorizationRepository authorizations;
    @Autowired JdbcClient jdbc;

    private static final Instant T0 = Instant.parse("2026-05-12T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-05-12T10:05:00Z");

    @BeforeEach
    void wipeTables() {
        jdbc.sql("DELETE FROM refunds").update();
        jdbc.sql("DELETE FROM authorizations").update();
    }

    @Test
    void saveAndLoadPendingRefund() {
        Capture capture = seedCaptureWith("USD", new BigDecimal("100.00"));
        Refund refund = Refund.initiate(
                AuthorizationIdHolder.parent.id(),
                capture.id(),
                capture.amount(),
                Money.zero("USD"),
                new Money(new BigDecimal("30.00"), "USD"),
                T0);

        refunds.save(refund);

        Refund loaded = refunds.findById(refund.id()).orElseThrow();
        assertThat(loaded.id()).isEqualTo(refund.id());
        assertThat(loaded.captureId()).isEqualTo(capture.id());
        assertThat(loaded.amount().amount()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(loaded.status()).isInstanceOf(Pending.class);
    }

    @Test
    void succeedTransitionPersistsViaUpsert() {
        Capture capture = seedCaptureWith("USD", new BigDecimal("100.00"));
        Refund pending = Refund.initiate(
                AuthorizationIdHolder.parent.id(),
                capture.id(),
                capture.amount(),
                Money.zero("USD"),
                new Money(new BigDecimal("30.00"), "USD"),
                T0);
        refunds.save(pending);

        Refund succeeded = pending.succeed(T1).next();
        refunds.save(succeeded);

        Refund loaded = refunds.findById(pending.id()).orElseThrow();
        assertThat(loaded.status()).isInstanceOf(Succeeded.class);
    }

    @Test
    void findByCaptureIdReturnsAllRefundsForThatCapture() {
        Capture capture = seedCaptureWith("USD", new BigDecimal("100.00"));
        Refund r1 = Refund.initiate(
                AuthorizationIdHolder.parent.id(), capture.id(),
                capture.amount(), Money.zero("USD"),
                new Money(new BigDecimal("20.00"), "USD"), T0);
        Refund r2 = Refund.initiate(
                AuthorizationIdHolder.parent.id(), capture.id(),
                capture.amount(), new Money(new BigDecimal("20.00"), "USD"),
                new Money(new BigDecimal("30.00"), "USD"), T1);
        refunds.save(r1);
        refunds.save(r2);

        List<Refund> all = refunds.findByCaptureId(capture.id());

        assertThat(all).hasSize(2);
        assertThat(all).extracting(Refund::id).containsExactlyInAnyOrder(r1.id(), r2.id());
    }

    @Test
    void succeededTotalForCaptureSumsOnlySucceeded() {
        Capture capture = seedCaptureWith("USD", new BigDecimal("100.00"));
        Refund pending = Refund.initiate(
                AuthorizationIdHolder.parent.id(), capture.id(),
                capture.amount(), Money.zero("USD"),
                new Money(new BigDecimal("20.00"), "USD"), T0);
        Refund succeeded = Refund.initiate(
                        AuthorizationIdHolder.parent.id(), capture.id(),
                        capture.amount(), Money.zero("USD"),
                        new Money(new BigDecimal("30.00"), "USD"), T0)
                .succeed(T1).next();
        refunds.save(pending);
        refunds.save(succeeded);

        Money total = refunds.succeededTotalForCapture(capture.id(), "USD");

        assertThat(total.amount()).isEqualByComparingTo(new BigDecimal("30.00"));
    }

    /**
     * Seeds a parent authorization with one SUCCEEDED capture so the
     * refund FK has something to point at. Capture identity is shared
     * via a static holder because we need the AuthorizationId across
     * test methods.
     */
    private Capture seedCaptureWith(String currency, BigDecimal amount) {
        UUID merchantId = UUID.randomUUID();
        Authorization auth = Authorization.initiate(
                        new MerchantId(merchantId),
                        new Money(new BigDecimal("100.00"), currency),
                        Instant.parse("2026-05-19T10:00:00Z"),
                        T0)
                .authorize(T0).next();
        Capture capture = new Capture(
                CaptureId.generate(),
                new Money(amount, currency),
                CaptureStatus.SUCCEEDED,
                T0,
                T0);
        Authorization withCapture = new Authorization(
                auth.id(), auth.merchantId(), auth.authorizedAmount(),
                auth.expiresAt(), auth.status(), List.of(capture));
        authorizations.save(withCapture);
        AuthorizationIdHolder.parent = withCapture;
        return capture;
    }

    private static class AuthorizationIdHolder {
        static Authorization parent;
    }
}
