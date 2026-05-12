package dev.dogukankat.reconcile.payment.refund;

import dev.dogukankat.reconcile.payment.authorization.AuthorizationId;
import dev.dogukankat.reconcile.payment.authorization.CaptureId;
import dev.dogukankat.reconcile.payment.authorization.Money;
import dev.dogukankat.reconcile.payment.refund.RefundStatus.Failed;
import dev.dogukankat.reconcile.payment.refund.RefundStatus.Pending;
import dev.dogukankat.reconcile.payment.refund.RefundStatus.Succeeded;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RefundRepository {

    private final JdbcClient jdbc;

    public RefundRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void save(Refund refund) {
        jdbc.sql("""
                        INSERT INTO refunds
                          (id, authorization_id, capture_id, amount, currency,
                           status, status_timestamp, status_reason)
                        VALUES (:id, :authorizationId, :captureId, :amount, :currency,
                                :status, :statusTimestamp, :statusReason)
                        ON CONFLICT (id) DO UPDATE SET
                          status = EXCLUDED.status,
                          status_timestamp = EXCLUDED.status_timestamp,
                          status_reason = EXCLUDED.status_reason,
                          updated_at = now()
                        """)
                .param("id", refund.id().value())
                .param("authorizationId", refund.authorizationId().value())
                .param("captureId", refund.captureId().value())
                .param("amount", refund.amount().amount())
                .param("currency", refund.amount().currency())
                .param("status", statusToName(refund.status()))
                .param("statusTimestamp", Timestamp.from(refund.status().timestamp()))
                .param("statusReason",
                        (refund.status() instanceof Failed f) ? f.reason() : null)
                .update();
    }

    @Transactional(readOnly = true)
    public Optional<Refund> findById(RefundId id) {
        return jdbc.sql("""
                        SELECT id, authorization_id, capture_id, amount, currency,
                               status, status_timestamp, status_reason
                        FROM refunds
                        WHERE id = :id
                        """)
                .param("id", id.value())
                .query(this::mapRefund)
                .optional();
    }

    @Transactional(readOnly = true)
    public List<Refund> findByCaptureId(CaptureId captureId) {
        return jdbc.sql("""
                        SELECT id, authorization_id, capture_id, amount, currency,
                               status, status_timestamp, status_reason
                        FROM refunds
                        WHERE capture_id = :captureId
                        ORDER BY status_timestamp
                        """)
                .param("captureId", captureId.value())
                .query(this::mapRefund)
                .list();
    }

    /**
     * Sum of SUCCEEDED refund amounts for the given capture. Used by
     * the application layer to feed Refund.initiate's invariant check.
     */
    @Transactional(readOnly = true)
    public Money succeededTotalForCapture(CaptureId captureId, String currency) {
        BigDecimal total = jdbc.sql("""
                        SELECT COALESCE(SUM(amount), 0)
                        FROM refunds
                        WHERE capture_id = :captureId
                          AND status = 'SUCCEEDED'
                        """)
                .param("captureId", captureId.value())
                .query(BigDecimal.class)
                .single();
        return new Money(total, currency);
    }

    private Refund mapRefund(ResultSet rs, int rowNum) throws SQLException {
        RefundId id = new RefundId(rs.getObject("id", UUID.class));
        AuthorizationId authId = new AuthorizationId(rs.getObject("authorization_id", UUID.class));
        CaptureId captureId = new CaptureId(rs.getObject("capture_id", UUID.class));
        String currency = rs.getString("currency").trim();
        Money amount = new Money(rs.getBigDecimal("amount"), currency);
        RefundStatus status = nameToStatus(
                rs.getString("status"),
                rs.getTimestamp("status_timestamp").toInstant(),
                rs.getString("status_reason"));
        return new Refund(id, authId, captureId, amount, status);
    }

    private static String statusToName(RefundStatus s) {
        return switch (s) {
            case Pending p -> "PENDING";
            case Succeeded ok -> "SUCCEEDED";
            case Failed f -> "FAILED";
        };
    }

    private static RefundStatus nameToStatus(String name, Instant timestamp, String reason) {
        return switch (name) {
            case "PENDING" -> new Pending(timestamp);
            case "SUCCEEDED" -> new Succeeded(timestamp);
            case "FAILED" -> new Failed(timestamp, reason);
            default -> throw new IllegalStateException("unknown refund status: " + name);
        };
    }
}
