package dev.dogukankat.reconcile.payment.refund;

import dev.dogukankat.reconcile.payment.authorization.AuthorizationId;
import dev.dogukankat.reconcile.payment.authorization.CaptureId;
import dev.dogukankat.reconcile.payment.authorization.Money;
import dev.dogukankat.reconcile.payment.event.DomainEvent.PaymentRefunded;
import dev.dogukankat.reconcile.payment.event.DomainEvent.RefundFailed;
import dev.dogukankat.reconcile.payment.refund.RefundStatus.Failed;
import dev.dogukankat.reconcile.payment.refund.RefundStatus.Pending;
import dev.dogukankat.reconcile.payment.refund.RefundStatus.Succeeded;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundTest {

    private static final Instant T0 = Instant.parse("2026-05-12T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-05-12T10:05:00Z");
    private static final AuthorizationId AUTH = AuthorizationId.generate();
    private static final CaptureId CAPTURE = CaptureId.generate();
    private static final Money CAPTURE_AMOUNT = new Money(new BigDecimal("100.00"), "USD");
    private static final Money NO_REFUNDS = Money.zero("USD");

    @Test
    void initiateStartsInPending() {
        Refund r = Refund.initiate(
                AUTH, CAPTURE, CAPTURE_AMOUNT, NO_REFUNDS,
                new Money(new BigDecimal("30.00"), "USD"), T0);

        assertThat(r.status()).isInstanceOf(Pending.class);
        assertThat(r.amount().amount()).isEqualByComparingTo(new BigDecimal("30.00"));
    }

    @Test
    void initiateRejectsAmountThatExceedsCapture() {
        Money existing = new Money(new BigDecimal("80.00"), "USD");
        Money requested = new Money(new BigDecimal("30.00"), "USD");

        assertThatThrownBy(() -> Refund.initiate(
                AUTH, CAPTURE, CAPTURE_AMOUNT, existing, requested, T0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void initiateRejectsZeroAmount() {
        assertThatThrownBy(() -> Refund.initiate(
                AUTH, CAPTURE, CAPTURE_AMOUNT, NO_REFUNDS,
                Money.zero("USD"), T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void succeedMovesPendingToSucceededAndEmitsPaymentRefunded() {
        Refund pending = Refund.initiate(
                AUTH, CAPTURE, CAPTURE_AMOUNT, NO_REFUNDS,
                new Money(new BigDecimal("30.00"), "USD"), T0);

        RefundResult result = pending.succeed(T1);

        assertThat(result.next().status()).isInstanceOf(Succeeded.class);
        assertThat(result.events()).hasSize(1).first().isInstanceOf(PaymentRefunded.class);
    }

    @Test
    void failMovesPendingToFailedWithReason() {
        Refund pending = Refund.initiate(
                AUTH, CAPTURE, CAPTURE_AMOUNT, NO_REFUNDS,
                new Money(new BigDecimal("30.00"), "USD"), T0);

        RefundResult result = pending.fail("network declined", T1);

        assertThat(result.next().status()).isInstanceOfSatisfying(
                Failed.class, f -> assertThat(f.reason()).isEqualTo("network declined"));
        assertThat(result.events()).hasSize(1).first().isInstanceOf(RefundFailed.class);
    }

    @Test
    void succeedFromNonPendingIsRejected() {
        Refund succeeded = Refund.initiate(
                        AUTH, CAPTURE, CAPTURE_AMOUNT, NO_REFUNDS,
                        new Money(new BigDecimal("30.00"), "USD"), T0)
                .succeed(T1).next();

        assertThatThrownBy(() -> succeeded.succeed(T1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires Pending");
    }

    @Test
    void paymentRefundedEventCarriesRefundAggregateType() {
        Refund pending = Refund.initiate(
                AUTH, CAPTURE, CAPTURE_AMOUNT, NO_REFUNDS,
                new Money(new BigDecimal("30.00"), "USD"), T0);

        RefundResult result = pending.succeed(T1);

        assertThat(result.events().get(0).aggregateType()).isEqualTo("refund");
    }
}
