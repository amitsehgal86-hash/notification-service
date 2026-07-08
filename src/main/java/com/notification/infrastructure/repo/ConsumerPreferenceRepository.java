package com.notification.infrastructure.repo;

import com.notification.model.ConsumerPreferences;
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

@Repository
public class ConsumerPreferenceRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ConsumerPreferenceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ConsumerPreferences> MAPPER = (rs, n) -> new ConsumerPreferences(
            rs.getObject("consumer_id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getBoolean("opted_out"),
            optInstant(rs.getObject("opted_out_at", OffsetDateTime.class)),
            rs.getString("opted_out_via"),
            rs.getString("timezone"),
            rs.getBoolean("sms_enabled"),
            rs.getBoolean("email_enabled")
    );

    private static Instant optInstant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }

    /**
     * FDCPA-critical strong read. This is the only DB in the app (the primary), so every read is strong.
     */
    public Optional<ConsumerPreferences> find(UUID tenantId, UUID consumerId) {
        List<ConsumerPreferences> rows = jdbc.query("""
                SELECT * FROM consumer_preferences WHERE consumer_id = :consumerId AND tenant_id = :tenantId
                """, new MapSqlParameterSource()
                .addValue("consumerId", consumerId)
                .addValue("tenantId", tenantId), MAPPER);
        return rows.stream().findFirst();
    }

    public void upsert(ConsumerPreferences p) {
        jdbc.update("""
                INSERT INTO consumer_preferences
                    (consumer_id, tenant_id, opted_out, opted_out_at, opted_out_via, timezone, sms_enabled, email_enabled)
                VALUES
                    (:consumerId, :tenantId, :optedOut, :optedOutAt, :optedOutVia, :timezone, :smsEnabled, :emailEnabled)
                ON CONFLICT (consumer_id, tenant_id) DO UPDATE SET
                    opted_out = EXCLUDED.opted_out,
                    opted_out_at = EXCLUDED.opted_out_at,
                    opted_out_via = EXCLUDED.opted_out_via,
                    timezone = EXCLUDED.timezone,
                    sms_enabled = EXCLUDED.sms_enabled,
                    email_enabled = EXCLUDED.email_enabled
                """, new MapSqlParameterSource()
                .addValue("consumerId", p.consumerId())
                .addValue("tenantId", p.tenantId())
                .addValue("optedOut", p.optedOut())
                .addValue("optedOutAt", p.optedOutAt() == null ? null : p.optedOutAt().atOffset(ZoneOffset.UTC))
                .addValue("optedOutVia", p.optedOutVia())
                .addValue("timezone", p.timezone())
                .addValue("smsEnabled", p.smsEnabled())
                .addValue("emailEnabled", p.emailEnabled()));
    }

    /**
     * Returns up to {@code limit} consumer IDs that are eligible to receive a notification right now:
     * not opted out, timezone currently within the FDCPA window, and not contacted within the
     * suppression window. All three filters are evaluated in a single DB query so the orchestrator
     * handles only consumers that are genuinely ready.
     */
    public List<UUID> findEligibleConsumerIds(UUID tenantId, List<String> openTimezones,
                                              Instant since, int limit) {
        if (openTimezones.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT cp.consumer_id
                FROM consumer_preferences cp
                WHERE cp.tenant_id = :tenantId
                  AND cp.opted_out = false
                  AND cp.timezone IN (:openTimezones)
                  AND NOT EXISTS (
                      SELECT 1 FROM notifications n
                      WHERE n.tenant_id = cp.tenant_id
                        AND n.consumer_id = cp.consumer_id
                        AND n.status IN ('SENT', 'DELIVERED')
                        AND n.sent_at > :since
                  )
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("openTimezones", openTimezones)
                .addValue("since", since.atOffset(ZoneOffset.UTC))
                .addValue("limit", limit),
                (rs, n) -> rs.getObject("consumer_id", UUID.class));
    }

    /** Synchronous strong write — opt-out is FDCPA-critical, no eventual consistency. */
    public boolean optOut(UUID tenantId, UUID consumerId, String via, Instant at) {
        int rows = jdbc.update("""
                UPDATE consumer_preferences
                SET opted_out = true, opted_out_at = :at, opted_out_via = :via
                WHERE consumer_id = :consumerId AND tenant_id = :tenantId
                """, new MapSqlParameterSource()
                .addValue("consumerId", consumerId)
                .addValue("tenantId", tenantId)
                .addValue("via", via)
                .addValue("at", at.atOffset(ZoneOffset.UTC)));
        return rows > 0;
    }
}
