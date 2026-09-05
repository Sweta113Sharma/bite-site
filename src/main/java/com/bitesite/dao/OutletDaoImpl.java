package com.bitesite.dao;

import com.bitesite.model.Outlet;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
            .acceptingOrders(rs.getBoolean("accepting_orders"))
            .opensAt(rs.getObject("opens_at", java.time.LocalTime.class))
            .closesAt(rs.getObject("closes_at", java.time.LocalTime.class))
            .contactPhone(rs.getString("contact_phone"))
            .latitude(rs.getObject("latitude", java.math.BigDecimal.class))
            .longitude(rs.getObject("longitude", java.math.BigDecimal.class))
            .notice(rs.getString("notice"))
            .createdAt(rs.getObject("created_at", LocalDateTime.class))
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
    public List<Outlet> findAllAcrossTenants() {
        return jdbcTemplate.query("SELECT * FROM outlets ORDER BY tenant_id, name", ROW_MAPPER);
    }

    @Override
    public Outlet save(Outlet outlet) {
        if (outlet.getId() == null) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO outlets (tenant_id, name, is_active, accepting_orders) VALUES (?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, outlet.getTenantId());
                ps.setString(2, outlet.getName());
                ps.setBoolean(3, outlet.isActive());
                ps.setBoolean(4, outlet.isAcceptingOrders());
                return ps;
            }, keyHolder);
            outlet.setId(keyHolder.getKey().longValue());
        } else {
            jdbcTemplate.update(
                    "UPDATE outlets SET name = ?, is_active = ?, accepting_orders = ? "
                            + "WHERE id = ? AND tenant_id = ?",
                    outlet.getName(), outlet.isActive(), outlet.isAcceptingOrders(), outlet.getId(),
                    outlet.getTenantId());
        }
        return findById(outlet.getId()).orElseThrow();
    }

    @Override
    public void updateSettings(Long id, Long tenantId, java.time.LocalTime opensAt,
            java.time.LocalTime closesAt, String contactPhone, String notice) {
        jdbcTemplate.update(
                "UPDATE outlets SET opens_at = ?, closes_at = ?, contact_phone = ?, notice = ? "
                        + "WHERE id = ? AND tenant_id = ?",
                opensAt, closesAt, contactPhone, notice, id, tenantId);
    }

    @Override
    public void updateAcceptingOrders(Long id, Long tenantId, boolean acceptingOrders) {
        jdbcTemplate.update(
                "UPDATE outlets SET accepting_orders = ? WHERE id = ? AND tenant_id = ?",
                acceptingOrders, id, tenantId);
    }

    @Override
    public int countOrders(Long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE outlet_id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }

    @Override
    public List<Long> findStaffUserIds(Long id) {
        return jdbcTemplate.queryForList("SELECT id FROM users WHERE outlet_id = ?", Long.class, id);
    }

    @Override
    @Transactional
    public void delete(Long id, Long tenantId) {
        // Menu items go with the outlet: they are its own catalog and mean nothing without
        // it. Safe to hard-delete only because callers refuse the whole operation when any
        // order exists, and an order_items row can only point at a menu item through one.
        jdbcTemplate.update("DELETE FROM menu_items WHERE outlet_id = ? AND tenant_id = ?", id, tenantId);
        jdbcTemplate.update("DELETE FROM outlets WHERE id = ? AND tenant_id = ?", id, tenantId);
    }
}
