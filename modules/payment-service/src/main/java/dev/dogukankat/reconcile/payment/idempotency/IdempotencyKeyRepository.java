package dev.dogukankat.reconcile.payment.idempotency;

import dev.dogukankat.reconcile.payment.authorization.MerchantId;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class IdempotencyKeyRepository {

    private final JdbcClient jdbc;

    public IdempotencyKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Atomically tries to claim ownership of (merchantId, key) for a new
     * request. Returns one of the four outcomes described in ADR-0006.
     * The race between concurrent reservers is decided by Postgres at
     * the unique-index level; the INSERT either succeeds (Inserted) or
     * loses, in which case we read the winning row and report what it
     * says.
     */
    @Transactional
    public IdempotencyResult tryReserve(
            MerchantId merchantId,
            String key,
            String requestHash,
            Instant now) {
        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_keys
                          (merchant_id, idempotency_key, request_hash, status, created_at)
                        VALUES (:merchantId, :key, :hash, 'IN_PROGRESS', :createdAt)
                        ON CONFLICT (merchant_id, idempotency_key) DO NOTHING
                        """)
                .param("merchantId", merchantId.value())
                .param("key", key)
                .param("hash", requestHash)
                .param("createdAt", Timestamp.from(now))
                .update();

        if (inserted == 1) {
            return new IdempotencyResult.Inserted();
        }

        return jdbc.sql("""
                        SELECT request_hash, status, response_status, response_body, resource_id
                        FROM idempotency_keys
                        WHERE merchant_id = :merchantId AND idempotency_key = :key
                        """)
                .param("merchantId", merchantId.value())
                .param("key", key)
                .query((rs, rowNum) -> mapExisting(rs, requestHash))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "idempotency row vanished mid-reservation: "
                                + merchantId + " / " + key));
    }

    /**
     * Marks a reservation COMPLETED and stores the response shape that
     * should be replayed for future requests carrying the same key.
     * Only flips IN_PROGRESS rows; a COMPLETED row is treated as a bug
     * because nothing should be completing twice.
     */
    @Transactional
    public void complete(
            MerchantId merchantId,
            String key,
            int responseStatus,
            String responseBody,
            UUID resourceId,
            Instant completedAt) {
        int updated = jdbc.sql("""
                        UPDATE idempotency_keys
                        SET status = 'COMPLETED',
                            response_status = :responseStatus,
                            response_body = CAST(:responseBody AS JSONB),
                            resource_id = :resourceId,
                            completed_at = :completedAt
                        WHERE merchant_id = :merchantId
                          AND idempotency_key = :key
                          AND status = 'IN_PROGRESS'
                        """)
                .param("merchantId", merchantId.value())
                .param("key", key)
                .param("responseStatus", responseStatus)
                .param("responseBody", responseBody)
                .param("resourceId", resourceId)
                .param("completedAt", Timestamp.from(completedAt))
                .update();

        if (updated == 0) {
            throw new IllegalStateException(
                    "no IN_PROGRESS idempotency record to complete: "
                            + merchantId + " / " + key);
        }
    }

    /**
     * Hard-deletes rows older than the cutoff; intended to be called by
     * a scheduled job once a day. The CHECK on the retention boundary
     * (24h) lives at the caller, not here — the repository is happy to
     * delete whatever it's pointed at.
     */
    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM idempotency_keys WHERE created_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }

    private IdempotencyResult mapExisting(ResultSet rs, String incomingHash)
            throws SQLException {
        String storedHash = rs.getString("request_hash");
        if (!storedHash.equals(incomingHash)) {
            return new IdempotencyResult.HashMismatch();
        }
        IdempotencyStatus status = IdempotencyStatus.valueOf(rs.getString("status"));
        if (status == IdempotencyStatus.IN_PROGRESS) {
            return new IdempotencyResult.InProgress();
        }
        return new IdempotencyResult.Completed(
                rs.getInt("response_status"),
                rs.getString("response_body"),
                rs.getObject("resource_id", UUID.class));
    }
}
