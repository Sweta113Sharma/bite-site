package com.bitesite.dao;

import com.bitesite.model.TechConfigEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class TechConfigDaoImpl implements TechConfigDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<TechConfigEntry> ROW_MAPPER = (rs, rowNum) -> TechConfigEntry.builder()
            .id(rs.getLong("id"))
            .tenantId(rs.getLong("tenant_id"))
            .configKey(rs.getString("config_key"))
            .configValue(rs.getString("config_value"))
            .updatedAt(rs.getObject("updated_at", LocalDateTime.class))
            .build();

    @Override
    public List<TechConfigEntry> findByTenantId(Long tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM tech_config WHERE tenant_id = ? ORDER BY config_key", ROW_MAPPER, tenantId);
    }

    @Override
    public Optional<String> getValue(Long tenantId, String key) {
        return jdbcTemplate.query(
                "SELECT config_value FROM tech_config WHERE tenant_id = ? AND config_key = ?",
                (rs, rowNum) -> rs.getString("config_value"), tenantId, key)
                .stream().findFirst();
    }

    @Override
    public void upsert(Long tenantId, String key, String value) {
        jdbcTemplate.update(
                "INSERT INTO tech_config (tenant_id, config_key, config_value) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE config_value = VALUES(config_value)",
                tenantId, key, value);
    }
}
