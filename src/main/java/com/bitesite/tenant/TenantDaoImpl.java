package com.bitesite.tenant;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class TenantDaoImpl implements TenantDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<Tenant> ROW_MAPPER = (rs, rowNum) -> Tenant.builder()
            .id(rs.getLong("id"))
            .name(rs.getString("name"))
            .logoPath(rs.getString("logo_path"))
            .status(TenantStatus.valueOf(rs.getString("status")))
            .createdAt(rs.getObject("created_at", LocalDateTime.class))
            .updatedAt(rs.getObject("updated_at", LocalDateTime.class))
            .build();

    @Override
    public Optional<Tenant> findById(Long id) {
        List<Tenant> results = jdbcTemplate.query("SELECT * FROM tenants WHERE id = ?", ROW_MAPPER, id);
        return results.stream().findFirst();
    }

    @Override
    public List<Tenant> findAll() {
        return jdbcTemplate.query("SELECT * FROM tenants ORDER BY created_at DESC", ROW_MAPPER);
    }

    @Override
    public List<Tenant> findActive() {
        return jdbcTemplate.query(
                "SELECT * FROM tenants WHERE status = 'ACTIVE' ORDER BY name", ROW_MAPPER);
    }

    @Override
    public Tenant save(Tenant tenant) {
        if (tenant.getId() == null) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO tenants (name, logo_path, status) VALUES (?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, tenant.getName());
                ps.setString(2, tenant.getLogoPath());
                ps.setString(3, tenant.getStatus().name());
                return ps;
            }, keyHolder);
            tenant.setId(keyHolder.getKey().longValue());
        } else {
            jdbcTemplate.update(
                    "UPDATE tenants SET name = ?, logo_path = ?, status = ? WHERE id = ?",
                    tenant.getName(), tenant.getLogoPath(), tenant.getStatus().name(), tenant.getId());
        }
        return findById(tenant.getId()).orElseThrow();
    }

    @Override
    public void updateLogoPath(Long id, String logoPath) {
        jdbcTemplate.update("UPDATE tenants SET logo_path = ? WHERE id = ?", logoPath, id);
    }

    @Override
    public void updateStatus(Long id, TenantStatus status) {
        jdbcTemplate.update("UPDATE tenants SET status = ? WHERE id = ?", status.name(), id);
    }
}
