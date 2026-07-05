package com.notification.infrastructure.repo;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class DeliveryLogRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public DeliveryLogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Idempotent per-attempt insert. provider_message_id is the dedup key for delivery-receipt
     * webhooks (Twilio/SendGrid retry receipts). Returns true if this is a new row.
     */
    public boolean insertIfAbsent(UUID notificationId, int attemptNumber, String status,
                                  String providerMessageId, String errorMessage) {
        int rows = jdbc.update("""
                INSERT INTO delivery_log
                    (notification_id, attempt_number, status, provider_message_id, error_message)
                VALUES
                    (:notificationId, :attemptNumber, :status, :providerMessageId, :errorMessage)
                ON CONFLICT (provider_message_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("notificationId", notificationId)
                .addValue("attemptNumber", attemptNumber)
                .addValue("status", status)
                .addValue("providerMessageId", providerMessageId)
                .addValue("errorMessage", errorMessage));
        return rows > 0;
    }

    /** Updates the delivery_log status for a provider message (delivery receipt). */
    public int updateStatusByProviderId(String providerMessageId, String status, String errorMessage) {
        return jdbc.update("""
                UPDATE delivery_log SET status = :status, error_message = :errorMessage
                WHERE provider_message_id = :providerMessageId
                """, new MapSqlParameterSource()
                .addValue("providerMessageId", providerMessageId)
                .addValue("status", status)
                .addValue("errorMessage", errorMessage));
    }

    public java.util.Optional<UUID> findNotificationIdByProviderId(String providerMessageId) {
        List<UUID> ids = jdbc.query("""
                SELECT notification_id FROM delivery_log WHERE provider_message_id = :pmid
                """, new MapSqlParameterSource().addValue("pmid", providerMessageId),
                (rs, n) -> rs.getObject("notification_id", UUID.class));
        return ids.stream().findFirst();
    }

    public List<Map<String, Object>> findByNotification(UUID notificationId) {
        return jdbc.queryForList("""
                SELECT attempt_number, status, provider_message_id, error_message, created_at
                FROM delivery_log WHERE notification_id = :id ORDER BY created_at ASC
                """, new MapSqlParameterSource().addValue("id", notificationId));
    }
}
