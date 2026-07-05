package com.notification.infrastructure.repo;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

@Repository
public class DeadLetterRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public DeadLetterRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Record a message that failed processing for later human review / reprocessing. */
    public void record(UUID tenantId, String queueName, String messageId, UUID notificationId,
                       String payloadJson, String errorMessage, Instant now) {
        jdbc.update("""
                INSERT INTO dead_letter_messages
                    (tenant_id, queue_name, message_id, notification_id, payload, error_message,
                     retry_count, first_failed_at, last_failed_at, status)
                VALUES
                    (:tenantId, :queueName, :messageId, :notificationId, CAST(:payload AS jsonb), :error,
                     0, :now, :now, 'PENDING_REVIEW')
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("queueName", queueName)
                .addValue("messageId", messageId)
                .addValue("notificationId", notificationId)
                .addValue("payload", payloadJson)
                .addValue("error", errorMessage)
                .addValue("now", now.atOffset(ZoneOffset.UTC)));
    }
}
