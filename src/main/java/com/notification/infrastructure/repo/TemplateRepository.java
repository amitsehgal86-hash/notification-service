package com.notification.infrastructure.repo;

import com.notification.model.Channel;
import com.notification.model.Template;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TemplateRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TemplateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Template> MAPPER = (rs, n) -> new Template(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("name"),
            Channel.valueOf(rs.getString("channel")),
            rs.getString("body_template"),
            rs.getBoolean("requires_miranda")
    );

    public Optional<Template> find(UUID tenantId, UUID id) {
        List<Template> rows = jdbc.query("""
                SELECT * FROM templates WHERE id = :id AND tenant_id = :tenantId
                """, new MapSqlParameterSource().addValue("id", id).addValue("tenantId", tenantId), MAPPER);
        return rows.stream().findFirst();
    }

    public List<Template> findByTenant(UUID tenantId) {
        return jdbc.query("SELECT * FROM templates WHERE tenant_id = :t ORDER BY created_at",
                new MapSqlParameterSource().addValue("t", tenantId), MAPPER);
    }

    /** Idempotent insert scoped to (tenant_id, name). Returns the id (new or existing). */
    public UUID insertIfAbsent(UUID tenantId, String name, Channel channel, String body, boolean requiresMiranda) {
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("name", name)
                .addValue("channel", channel.name())
                .addValue("body", body)
                .addValue("requiresMiranda", requiresMiranda);
        List<UUID> inserted = jdbc.query("""
                INSERT INTO templates (tenant_id, name, channel, body_template, requires_miranda)
                VALUES (:tenantId, :name, :channel, :body, :requiresMiranda)
                ON CONFLICT (tenant_id, name) DO NOTHING
                RETURNING id
                """, params, (rs, n) -> rs.getObject("id", UUID.class));
        if (!inserted.isEmpty()) return inserted.get(0);
        return jdbc.queryForObject("""
                SELECT id FROM templates WHERE tenant_id = :tenantId AND name = :name
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("name", name), UUID.class);
    }
}
