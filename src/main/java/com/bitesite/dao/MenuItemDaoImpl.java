package com.bitesite.dao;

import com.bitesite.model.MenuItem;
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
public class MenuItemDaoImpl implements MenuItemDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<MenuItem> ROW_MAPPER = (rs, rowNum) -> MenuItem.builder()
            .id(rs.getLong("id"))
            .tenantId(rs.getLong("tenant_id"))
            .outletId(rs.getLong("outlet_id"))
            .name(rs.getString("name"))
            .category(rs.getString("category"))
            .price(rs.getBigDecimal("price"))
            .discountPrice(rs.getBigDecimal("discount_price"))
            .discountPercent(rs.getBigDecimal("discount_percent"))
            .available(rs.getBoolean("is_available"))
            .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
            .updatedAt(rs.getTimestamp("updated_at").toLocalDateTime())
            .build();

    @Override
    public Optional<MenuItem> findByIdAndTenantId(Long id, Long tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM menu_items WHERE id = ? AND tenant_id = ?", ROW_MAPPER, id, tenantId)
                .stream().findFirst();
    }

    @Override
    public List<MenuItem> findByOutletId(Long outletId, Long tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM menu_items WHERE outlet_id = ? AND tenant_id = ? ORDER BY category, name",
                ROW_MAPPER, outletId, tenantId);
    }

    @Override
    public List<MenuItem> findAvailableByOutletId(Long outletId, Long tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM menu_items WHERE outlet_id = ? AND tenant_id = ? AND is_available = TRUE "
                        + "ORDER BY category, name",
                ROW_MAPPER, outletId, tenantId);
    }

    @Override
    public MenuItem save(MenuItem item) {
        if (item.getId() == null) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO menu_items (tenant_id, outlet_id, name, category, price, discount_price, "
                                + "discount_percent, is_available) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, item.getTenantId());
                ps.setLong(2, item.getOutletId());
                ps.setString(3, item.getName());
                ps.setString(4, item.getCategory());
                ps.setBigDecimal(5, item.getPrice());
                ps.setBigDecimal(6, item.getDiscountPrice());
                ps.setBigDecimal(7, item.getDiscountPercent());
                ps.setBoolean(8, item.isAvailable());
                return ps;
            }, keyHolder);
            item.setId(keyHolder.getKey().longValue());
        } else {
            jdbcTemplate.update(
                    "UPDATE menu_items SET name = ?, category = ?, price = ?, discount_price = ?, "
                            + "discount_percent = ?, is_available = ? WHERE id = ? AND tenant_id = ?",
                    item.getName(), item.getCategory(), item.getPrice(), item.getDiscountPrice(),
                    item.getDiscountPercent(), item.isAvailable(), item.getId(), item.getTenantId());
        }
        return findByIdAndTenantId(item.getId(), item.getTenantId()).orElseThrow();
    }

    @Override
    public void updateAvailability(Long id, Long tenantId, boolean available) {
        jdbcTemplate.update(
                "UPDATE menu_items SET is_available = ? WHERE id = ? AND tenant_id = ?", available, id, tenantId);
    }

    @Override
    public void delete(Long id, Long tenantId) {
        jdbcTemplate.update("DELETE FROM menu_items WHERE id = ? AND tenant_id = ?", id, tenantId);
    }
}
