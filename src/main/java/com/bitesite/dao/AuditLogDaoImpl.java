package com.bitesite.dao;

import com.bitesite.model.AuditLogEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class AuditLogDaoImpl implements AuditLogDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<AuditLogEntry> ROW_MAPPER = (rs, rowNum) -> AuditLogEntry.builder()
            .id(rs.getLong("id"))
            .actorUserId(rs.getObject("actor_user_id", Long.class))
            .tenantId(rs.getObject("tenant_id", Long.class))
            .entityType(rs.getString("entity_type"))
            .entityId(rs.getObject("entity_id", Long.class))
            .action(rs.getString("action"))
            .beforeJson(rs.getString("before_json"))
            .afterJson(rs.getString("after_json"))
            .createdAt(rs.getObject("created_at", LocalDateTime.class))
            .build();

    @Override
    public void save(AuditLogEntry entry) {
        jdbcTemplate.update(
                "INSERT INTO audit_log (actor_user_id, tenant_id, entity_type, entity_id, action, before_json, after_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                entry.getActorUserId(), entry.getTenantId(), entry.getEntityType(), entry.getEntityId(),
                entry.getAction(), entry.getBeforeJson(), entry.getAfterJson());
    }

    @Override
    public List<AuditLogEntry> findByTenantId(Long tenantId, int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM audit_log WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?",
                ROW_MAPPER, tenantId, limit);
    }

    @Override
    public List<AuditLogEntry> findByEntity(String entityType, Long entityId) {
        return jdbcTemplate.query(
                "SELECT * FROM audit_log WHERE entity_type = ? AND entity_id = ? ORDER BY created_at DESC",
                ROW_MAPPER, entityType, entityId);
    }
}
