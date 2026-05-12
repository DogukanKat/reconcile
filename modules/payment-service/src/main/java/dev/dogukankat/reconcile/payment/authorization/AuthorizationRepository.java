package dev.dogukankat.reconcile.payment.authorization;

import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.AuthFailed;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.Authorized;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.Expired;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.Initiated;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.Voided;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AuthorizationRepository {

    private final JdbcClient jdbc;

    public AuthorizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void save(Authorization authorization) {
        upsertAuthorization(authorization);
        jdbc.sql("DELETE FROM captures WHERE authorization_id = :authorizationId")
                .param("authorizationId", authorization.id().value())
                .update();
        for (Capture capture : authorization.captures()) {
            insertCapture(authorization.id(), capture);
        }
    }

    @Transactional(readOnly = true)
    public Optional<Authorization> findById(AuthorizationId id) {
        Optional<Authorization> shell = jdbc.sql("""
                        SELECT id, merchant_id, authorized_amount, currency, expires_at,
                               status, status_timestamp, status_reason
                        FROM authorizations
                        WHERE id = :id
                        """)
                .param("id", id.value())
                .query(this::mapAuthorizationWithoutCaptures)
                .optional();
        if (shell.isEmpty()) {
            return Optional.empty();
        }
        Authorization a = shell.get();
        List<Capture> captures = loadCaptures(a.id(), a.authorizedAmount().currency());
        return Optional.of(withCaptures(a, captures));
    }

    @Transactional(readOnly = true)
    public Optional<Authorization> findByCaptureId(CaptureId captureId) {
        return jdbc.sql("""
                        SELECT authorization_id FROM captures WHERE id = :captureId
                        """)
                .param("captureId", captureId.value())
                .query((rs, rowNum) -> new AuthorizationId(rs.getObject("authorization_id", UUID.class)))
                .optional()
                .flatMap(this::findById);
    }

    private void upsertAuthorization(Authorization a) {
        AuthorizationStatus s = a.status();
        String reason = (s instanceof AuthFailed af) ? af.reason() : null;
        jdbc.sql("""
                        INSERT INTO authorizations
                          (id, merchant_id, authorized_amount, currency, expires_at,
                           status, status_timestamp, status_reason)
                        VALUES (:id, :merchantId, :amount, :currency, :expiresAt,
                                :status, :statusTimestamp, :statusReason)
                        ON CONFLICT (id) DO UPDATE SET
                          status = EXCLUDED.status,
                          status_timestamp = EXCLUDED.status_timestamp,
                          status_reason = EXCLUDED.status_reason,
                          updated_at = now()
                        """)
                .param("id", a.id().value())
                .param("merchantId", a.merchantId().value())
                .param("amount", a.authorizedAmount().amount())
                .param("currency", a.authorizedAmount().currency())
                .param("expiresAt", Timestamp.from(a.expiresAt()))
                .param("status", statusToName(s))
                .param("statusTimestamp", Timestamp.from(s.timestamp()))
                .param("statusReason", reason)
                .update();
    }

    private void insertCapture(AuthorizationId authId, Capture c) {
        jdbc.sql("""
                        INSERT INTO captures
                          (id, authorization_id, amount, status, submitted_at, completed_at)
                        VALUES (:id, :authorizationId, :amount, :status,
                                :submittedAt, :completedAt)
                        """)
                .param("id", c.id().value())
                .param("authorizationId", authId.value())
                .param("amount", c.amount().amount())
                .param("status", c.status().name())
                .param("submittedAt", Timestamp.from(c.submittedAt()))
                .param("completedAt",
                        c.completedAt() == null ? null : Timestamp.from(c.completedAt()))
                .update();
    }

    private List<Capture> loadCaptures(AuthorizationId authId, String currency) {
        return jdbc.sql("""
                        SELECT id, amount, status, submitted_at, completed_at
                        FROM captures
                        WHERE authorization_id = :authorizationId
                        ORDER BY submitted_at
                        """)
                .param("authorizationId", authId.value())
                .query((rs, rowNum) -> new Capture(
                        new CaptureId(rs.getObject("id", UUID.class)),
                        new Money(rs.getBigDecimal("amount"), currency),
                        CaptureStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("submitted_at").toInstant(),
                        rs.getTimestamp("completed_at") == null
                                ? null
                                : rs.getTimestamp("completed_at").toInstant()))
                .list();
    }

    private Authorization mapAuthorizationWithoutCaptures(ResultSet rs, int rowNum)
            throws SQLException {
        AuthorizationId id = new AuthorizationId(rs.getObject("id", UUID.class));
        MerchantId merchantId = new MerchantId(rs.getObject("merchant_id", UUID.class));
        String currency = rs.getString("currency").trim();
        Money amount = new Money(rs.getBigDecimal("authorized_amount"), currency);
        Instant expiresAt = rs.getTimestamp("expires_at").toInstant();
        AuthorizationStatus status = nameToStatus(
                rs.getString("status"),
                rs.getTimestamp("status_timestamp").toInstant(),
                rs.getString("status_reason"));
        return new Authorization(id, merchantId, amount, expiresAt, status, List.of());
    }

    private static Authorization withCaptures(Authorization a, List<Capture> captures) {
        return new Authorization(
                a.id(), a.merchantId(), a.authorizedAmount(), a.expiresAt(),
                a.status(), captures);
    }

    private static String statusToName(AuthorizationStatus s) {
        return switch (s) {
            case Initiated i -> "INITIATED";
            case Authorized a -> "AUTHORIZED";
            case Voided v -> "VOIDED";
            case Expired e -> "EXPIRED";
            case AuthFailed af -> "AUTH_FAILED";
        };
    }

    private static AuthorizationStatus nameToStatus(
            String name, Instant timestamp, String reason) {
        return switch (name) {
            case "INITIATED" -> new Initiated(timestamp);
            case "AUTHORIZED" -> new Authorized(timestamp);
            case "VOIDED" -> new Voided(timestamp);
            case "EXPIRED" -> new Expired(timestamp);
            case "AUTH_FAILED" -> new AuthFailed(timestamp, reason);
            default -> throw new IllegalStateException("unknown status in database: " + name);
        };
    }
}
