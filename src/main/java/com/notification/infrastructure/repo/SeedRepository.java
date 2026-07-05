package com.notification.infrastructure.repo;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Bulk data generation for demos. Uses server-side {@code generate_series} so 1M rows are
 * created inside PostgreSQL in seconds — no per-row round trips from Java.
 */
@Repository
public class SeedRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SeedRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert {@code count} consumer_preferences rows for a tenant. Randomizes timezone across US
     * zones (for realistic FDCPA windows) and opts out ~2% of consumers.
     */
    public int seedConsumers(UUID tenantId, long count) {
        return jdbc.update("""
                INSERT INTO consumer_preferences
                    (consumer_id, tenant_id, opted_out, timezone, sms_enabled, email_enabled)
                SELECT
                    gen_random_uuid(),
                    :tenantId,
                    (g % 50 = 0),
                    (ARRAY['America/New_York','America/Chicago','America/Denver',
                           'America/Los_Angeles','America/Phoenix'])[1 + (g % 5)],
                    true,
                    true
                FROM generate_series(1, :count) AS g
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("count", count));
    }

    /**
     * Seed recent history: for a random {@code fraction} of the tenant's consumers, insert a SENT
     * notification dated 1 day ago. Those consumers are then inside the 3-day window and any new
     * notification to them will be SUPPRESSED — demonstrating the rule.
     */
    public int seedRecentHistory(UUID tenantId, UUID templateId, double fraction) {
        return jdbc.update("""
                INSERT INTO notifications
                    (tenant_id, consumer_id, template_id, channel, status, idempotency_key, sent_at, created_at)
                SELECT
                    tenant_id, consumer_id, :templateId, 'SMS', 'SENT',
                    'seed-hist-' || consumer_id,
                    now() - interval '1 day',
                    now() - interval '1 day'
                FROM consumer_preferences
                WHERE tenant_id = :tenantId AND random() < :fraction
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("templateId", templateId)
                .addValue("fraction", fraction));
    }

    /**
     * Sample up to {@code limit} consumer ids for a tenant (drives the demo load). Ordered by the
     * PK so the sample is DETERMINISTIC across calls — re-running the load hits the same consumers,
     * which is what lets the 3-day suppression rule be demonstrated on a second run.
     */
    public List<UUID> sampleConsumers(UUID tenantId, int limit) {
        return jdbc.query("""
                SELECT consumer_id FROM consumer_preferences WHERE tenant_id = :tenantId
                ORDER BY consumer_id LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("limit", limit), (rs, n) -> rs.getObject("consumer_id", UUID.class));
    }

    public long countConsumers(UUID tenantId) {
        Long c = jdbc.queryForObject("SELECT count(*) FROM consumer_preferences WHERE tenant_id = :t",
                new MapSqlParameterSource().addValue("t", tenantId), Long.class);
        return c == null ? 0 : c;
    }
}
