package com.bitesite.dao;

import com.bitesite.model.Outlet;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class OutletDaoImpl implements OutletDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<Outlet> ROW_MAPPER = (rs, rowNum) -> Outlet.builder()
            .id(rs.getLong("id"))
            .tenantId(rs.getLong("tenant_id"))
            .name(rs.getString("name"))
            .active(rs.getBoolean("is_active"))
            .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
            .build();

    @Override
    public Optional<Outlet> findById(Long id) {
        return jdbcTemplate.query("SELECT * FROM outlets WHERE id = ?", ROW_MAPPER, id)
                .stream().findFirst();
    }

    @Override
    public Optional<Outlet> findByIdAndTenantId(Long id, Long tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM outlets WHERE id = ? AND tenant_id = ?", ROW_MAPPER, id, tenantId)
                .stream().findFirst();
    }

    @Override
    public List<Outlet> findByTenantId(Long tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM outlets WHERE tenant_id = ? ORDER BY name", ROW_MAPPER, tenantId);
    }

    @Override
    public List<Outlet> findActiveByTenantId(Long tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM outlets WHERE tenant_id = ? AND is_active = TRUE ORDER BY name", ROW_MAPPER, tenantId);
    }

    @Override
    public Outlet save(Outlet outlet) {
        if (outlet.getId() == null) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO outlets (tenant_id, name, is_active) VALUES (?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, outlet.getTenantId());
                ps.setString(2, outlet.getName());
                ps.setBoolean(3, outlet.isActive());
                return ps;
            }, keyHolder);
            outlet.setId(keyHolder.getKey().longValue());
        } else {
            jdbcTemplate.update(
                    "UPDATE outlets SET name = ?, is_active = ? WHERE id = ? AND tenant_id = ?",
                    outlet.getName(), outlet.isActive(), outlet.getId(), outlet.getTenantId());
        }
        return findById(outlet.getId()).orElseThrow();
    }
}
