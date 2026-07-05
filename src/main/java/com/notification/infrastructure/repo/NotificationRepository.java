package com.notification.infrastructure.repo;

import com.notification.model.Channel;
import com.notification.model.Notification;
import com.notification.model.NotificationStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Repository
public class NotificationRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final Timer suppressionDbTimer;

    public NotificationRepository(NamedParameterJdbcTemplate jdbc, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.suppressionDbTimer = meterRegistry.timer("notification.suppression.query.db");
    }

    /** Result of an idempotent insert: the row id, and whether we created it (vs. found an existing one). */
    public record InsertResult(UUID id, boolean created) {}

    private static final RowMapper<Notification> MAPPER = (rs, n) -> new Notification(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("consumer_id", UUID.class),
            rs.getObject("template_id", UUID.class),
            Channel.valueOf(rs.getString("channel")),
            NotificationStatus.valueOf(rs.getString("status")),
            rs.getString("idempotency_key"),
            toInstant(rs.getObject("scheduled_at", OffsetDateTime.class)),
            toInstant(rs.getObject("sent_at", OffsetDateTime.class)),
            toInstant(rs.getObject("created_at", OffsetDateTime.class))
    );

    private static Instant toInstant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }

    private static OffsetDateTime toOdt(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }

    /**
     * Idempotent insert scoped to (tenant_id, idempotency_key). Returns the id of the new row,
     * or the id of the pre-existing row if this idempotency key was already seen.
     */
    public InsertResult insertIfAbsent(UUID tenantId, UUID consumerId, UUID templateId,
                                       Channel channel, String idempotencyKey, Instant scheduledAt) {
        NotificationStatus initial = scheduledAt != null ? NotificationStatus.HELD : NotificationStatus.PENDING;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("consumerId", consumerId)
                .addValue("templateId", templateId)
                .addValue("channel", channel.name())
                .addValue("status", initial.name())
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("scheduledAt", toOdt(scheduledAt));

        List<UUID> inserted = jdbc.query("""
                INSERT INTO notifications
                    (tenant_id, consumer_id, template_id, channel, status, idempotency_key, scheduled_at)
                VALUES
                    (:tenantId, :consumerId, :templateId, :channel, :status, :idempotencyKey, :scheduledAt)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                RETURNING id
                """, params, (rs, n) -> rs.getObject("id", UUID.class));

        if (!inserted.isEmpty()) {
            return new InsertResult(inserted.get(0), true);
        }
        // Conflict: return the existing row's id
        UUID existing = jdbc.queryForObject("""
                SELECT id FROM notifications
                WHERE tenant_id = :tenantId AND idempotency_key = :idempotencyKey
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("idempotencyKey", idempotencyKey), UUID.class);
        return new InsertResult(existing, false);
    }

    public Optional<Notification> findById(UUID tenantId, UUID id) {
        List<Notification> rows = jdbc.query("""
                SELECT * FROM notifications WHERE id = :id AND tenant_id = :tenantId
                """, new MapSqlParameterSource().addValue("id", id).addValue("tenantId", tenantId), MAPPER);
        return rows.stream().findFirst();
    }

    /** Mark SENT and stamp sent_at in one statement (same TX as the send). */
    public void markSent(UUID id, Instant sentAt) {
        jdbc.update("""
                UPDATE notifications SET status = 'SENT', sent_at = :sentAt WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("sentAt", toOdt(sentAt)));
    }

    public void updateStatus(UUID id, NotificationStatus status) {
        jdbc.update("UPDATE notifications SET status = :status WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("status", status.name()));
    }

    /** HOLD outside the FDCPA window: set status HELD + scheduled_at. */
    public void hold(UUID id, Instant scheduledAt) {
        jdbc.update("""
                UPDATE notifications SET status = 'HELD', scheduled_at = :scheduledAt WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("scheduledAt", toOdt(scheduledAt)));
    }

    /**
     * Atomically claim due HELD rows, flipping them to PENDING. FOR UPDATE SKIP LOCKED prevents
     * two scheduler instances from double-releasing the same row. Returns the claimed rows.
     */
    public List<Notification> claimHeld(Instant now, int limit) {
        return jdbc.query("""
                UPDATE notifications
                SET status = 'PENDING'
                WHERE id IN (
                    SELECT id FROM notifications
                    WHERE status = 'HELD' AND scheduled_at <= :now
                    ORDER BY scheduled_at ASC
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING *
                """, new MapSqlParameterSource().addValue("now", toOdt(now)).addValue("limit", limit), MAPPER);
    }

    /**
     * The 3-day rule's core query: has this consumer been contacted (SENT/DELIVERED) within the window?
     */
    public boolean wasContactedSince(UUID tenantId, UUID consumerId, Instant since) {
        long start = System.nanoTime();
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM notifications
                    WHERE tenant_id = :tenantId
                      AND consumer_id = :consumerId
                      AND status IN ('SENT', 'DELIVERED')
                      AND sent_at > :since
                )
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("consumerId", consumerId)
                .addValue("since", toOdt(since)), Boolean.class);
        suppressionDbTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Returns the most recently sent/delivered notifications for a tenant, ordered by sent_at DESC.
     * Uses the partial composite index (status IN ('SENT','DELIVERED')) for the filter,
     * then sorts by sent_at. Demonstrates query performance at large table sizes.
     */
    public List<Notification> listRecentSends(UUID tenantId, int limit) {
        return jdbc.query("""
                SELECT * FROM notifications
                WHERE tenant_id = :tenantId
                  AND status IN ('SENT', 'DELIVERED')
                ORDER BY sent_at DESC
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("limit", limit), MAPPER);
    }

    /** Simple filtered listing for GET /v1/notifications. */
    public List<Notification> list(UUID tenantId, UUID consumerId, NotificationStatus status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM notifications WHERE tenant_id = :tenantId");
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("limit", limit);
        if (consumerId != null) {
            sql.append(" AND consumer_id = :consumerId");
            params.addValue("consumerId", consumerId);
        }
        if (status != null) {
            sql.append(" AND status = :status");
            params.addValue("status", status.name());
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        return jdbc.query(sql.toString(), params, MAPPER);
    }

    public long count(UUID tenantId) {
        Long c = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE tenant_id = :t",
                new MapSqlParameterSource().addValue("t", tenantId), Long.class);
        return c == null ? 0 : c;
    }
}
