package com.notification.infrastructure.repo;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class OutboxRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public OutboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Write an outbox row in the SAME transaction as the business state change.
     * ON CONFLICT (aggregate_id, event_type) DO NOTHING keeps it idempotent under redelivery.
     */
    public void saveIfAbsent(UUID aggregateId, String aggregateType, String eventType, String payloadJson) {
        jdbc.update("""
                INSERT INTO outbox_events (aggregate_id, aggregate_type, event_type, payload)
                VALUES (:aggregateId, :aggregateType, :eventType, CAST(:payload AS jsonb))
                ON CONFLICT (aggregate_id, event_type) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("aggregateId", aggregateId)
                .addValue("aggregateType", aggregateType)
                .addValue("eventType", eventType)
                .addValue("payload", payloadJson));
    }

    public List<Map<String, Object>> findPending(int limit) {
        return jdbc.queryForList("""
                SELECT id, aggregate_id, aggregate_type, event_type, payload
                FROM outbox_events
                WHERE status = 'pending'
                ORDER BY created_at ASC
                LIMIT :limit
                """, new MapSqlParameterSource().addValue("limit", limit));
    }

    public void markPublished(UUID id) {
        jdbc.update("""
                UPDATE outbox_events SET status = 'published', published_at = now() WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id));
    }
}
