package dev.dogukankat.reconcile.payment.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void append(OutboxEntry entry) {
        jdbc.sql("""
                        INSERT INTO outbox
                          (id, aggregatetype, aggregateid, type, payload, occurred_at)
                        VALUES (:id, :aggregateType, :aggregateId, :eventType,
                                CAST(:payload AS JSONB), :occurredAt)
                        """)
                .param("id", entry.id())
                .param("aggregateType", entry.aggregateType())
                .param("aggregateId", entry.aggregateId())
                .param("eventType", entry.eventType())
                .param("payload", entry.payload())
                .param("occurredAt", Timestamp.from(entry.occurredAt()))
                .update();
    }

    /** Used by tests; production reads go through Debezium, not this method. */
    public List<OutboxEntry> findByAggregateId(UUID aggregateId) {
        return jdbc.sql("""
                        SELECT id, aggregatetype, aggregateid, type, payload, occurred_at
                        FROM outbox
                        WHERE aggregateid = :aggregateId
                        ORDER BY occurred_at, created_at
                        """)
                .param("aggregateId", aggregateId)
                .query((rs, rowNum) -> new OutboxEntry(
                        rs.getObject("id", UUID.class),
                        rs.getString("aggregatetype"),
                        rs.getObject("aggregateid", UUID.class),
                        rs.getString("type"),
                        rs.getString("payload"),
                        rs.getTimestamp("occurred_at").toInstant()))
                .list();
    }
}
