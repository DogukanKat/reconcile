package dev.dogukankat.reconcile.payment.refund;

import dev.dogukankat.reconcile.payment.authorization.AuthorizationId;
import dev.dogukankat.reconcile.payment.authorization.CaptureId;
import dev.dogukankat.reconcile.payment.authorization.Money;
import dev.dogukankat.reconcile.payment.event.DomainEvent.PaymentRefunded;
import dev.dogukankat.reconcile.payment.event.DomainEvent.RefundFailed;
import dev.dogukankat.reconcile.payment.refund.RefundStatus.Failed;
import dev.dogukankat.reconcile.payment.refund.RefundStatus.Pending;
import dev.dogukankat.reconcile.payment.refund.RefundStatus.Succeeded;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Refund aggregate. Lives independently of the Authorization aggregate
 * (ADR-0001); the only ties are by identifier — captureId points at
 * the parent capture, authorizationId is carried through so the outbox
 * partition key (ADR-0005) stays consistent across the related events.
 *
 * The cross-aggregate invariant (sum of refunds against a capture must
 * not exceed the capture's amount) is enforced in initiate(), which
 * takes the capture's amount and the running total of succeeded refunds
 * as input. ADR-0001 notes this assumes the capture amount is immutable
 * once captured — it is, in this model.
 */
public record Refund(
        RefundId id,
        AuthorizationId authorizationId,
        CaptureId captureId,
        Money amount,
        RefundStatus status) {

    public Refund {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(authorizationId, "authorizationId");
        Objects.requireNonNull(captureId, "captureId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(status, "status");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("refund amount must be positive: " + amount);
        }
    }

    public static Refund initiate(
            AuthorizationId authorizationId,
            CaptureId captureId,
            Money captureAmount,
            Money existingRefundsTotal,
            Money requestedAmount,
            Instant submittedAt) {
        Objects.requireNonNull(captureAmount, "captureAmount");
        Objects.requireNonNull(existingRefundsTotal, "existingRefundsTotal");
        Objects.requireNonNull(requestedAmount, "requestedAmount");
        if (!requestedAmount.isPositive()) {
            throw new IllegalArgumentException(
                    "refund amount must be positive: " + requestedAmount);
        }
        Money totalAfter = existingRefundsTotal.add(requestedAmount);
        if (!totalAfter.isLessThanOrEqualTo(captureAmount)) {
            throw new IllegalStateException(
                    "refund exceeds remaining capture capacity; capture="
                            + captureAmount + " alreadyRefunded=" + existingRefundsTotal
                            + " requested=" + requestedAmount);
        }
        return new Refund(
                RefundId.generate(),
                authorizationId,
                captureId,
                requestedAmount,
                new Pending(submittedAt));
    }

    public RefundResult succeed(Instant completedAt) {
        requireStatus(Pending.class, "succeed");
        Refund next = withStatus(new Succeeded(completedAt));
        return new RefundResult(
                next,
                List.of(new PaymentRefunded(id, captureId, authorizationId, amount, completedAt)));
    }

    public RefundResult fail(String reason, Instant completedAt) {
        requireStatus(Pending.class, "fail");
        Objects.requireNonNull(reason, "reason");
        Refund next = withStatus(new Failed(completedAt, reason));
        return new RefundResult(
                next,
                List.of(new RefundFailed(id, captureId, authorizationId, reason, completedAt)));
    }

    private Refund withStatus(RefundStatus newStatus) {
        return new Refund(id, authorizationId, captureId, amount, newStatus);
    }

    private void requireStatus(Class<? extends RefundStatus> expected, String op) {
        if (!expected.isInstance(status)) {
            throw new IllegalStateException(
                    op + " requires " + expected.getSimpleName()
                            + ", was " + status.getClass().getSimpleName());
        }
    }
}
