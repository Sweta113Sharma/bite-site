package com.bitesite.dao;

import com.bitesite.model.MenuItem;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MenuItemDaoImpl implements MenuItemDao {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Every read joins the category for its name. `category` is no longer a column on
     * menu_items (V18), but the model still exposes it: the illustration matcher and the
     * student menu's grouping both key off the text. Aliasing it here keeps the row mapper
     * and every caller unchanged.
     */
    private static final String SELECT_ITEM =
            "SELECT mi.*, c.name AS category FROM menu_items mi "
                    + "JOIN categories c ON c.id = mi.category_id ";

    private static final RowMapper<MenuItem> ROW_MAPPER = (rs, rowNum) -> MenuItem.builder()
            .id(rs.getLong("id"))
            .tenantId(rs.getLong("tenant_id"))
            .outletId(rs.getLong("outlet_id"))
            .name(rs.getString("name"))
            .categoryId(rs.getLong("category_id"))
            .category(rs.getString("category"))
            .photoPath(rs.getString("photo_path"))
            .price(rs.getBigDecimal("price"))
            .discountPrice(rs.getBigDecimal("discount_price"))
            .discountPercent(rs.getBigDecimal("discount_percent"))
            .available(rs.getBoolean("is_available"))
            .dailyLimit(rs.getObject("daily_limit", Integer.class))
            .createdAt(rs.getObject("created_at", LocalDateTime.class))
            .updatedAt(rs.getObject("updated_at", LocalDateTime.class))
            .build();

    @Override
    public Optional<MenuItem> findByIdAndTenantId(Long id, Long tenantId) {
        return jdbcTemplate.query(
                SELECT_ITEM + "WHERE mi.id = ? AND mi.tenant_id = ?", ROW_MAPPER, id, tenantId)
                .stream().findFirst();
    }

    @Override
    public List<MenuItem> findByOutletId(Long outletId, Long tenantId) {
        return jdbcTemplate.query(
                SELECT_ITEM + "WHERE mi.outlet_id = ? AND mi.tenant_id = ? "
                        + "ORDER BY c.sort_order, c.name, mi.name",
                ROW_MAPPER, outletId, tenantId);
    }

    @Override
    public List<MenuItem> findAvailableByOutletId(Long outletId, Long tenantId) {
        return jdbcTemplate.query(
                SELECT_ITEM + "WHERE mi.outlet_id = ? AND mi.tenant_id = ? AND mi.is_available = TRUE "
                        + "ORDER BY c.sort_order, c.name, mi.name",
                ROW_MAPPER, outletId, tenantId);
    }

    @Override
    public MenuItem save(MenuItem item) {
        if (item.getId() == null) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO menu_items (tenant_id, outlet_id, name, category_id, photo_path, price, "
                                + "discount_price, discount_percent, is_available, daily_limit) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, item.getTenantId());
                ps.setLong(2, item.getOutletId());
                ps.setString(3, item.getName());
                ps.setLong(4, item.getCategoryId());
                ps.setString(5, item.getPhotoPath());
                ps.setBigDecimal(6, item.getPrice());
                ps.setBigDecimal(7, item.getDiscountPrice());
                ps.setBigDecimal(8, item.getDiscountPercent());
                ps.setBoolean(9, item.isAvailable());
                ps.setObject(10, item.getDailyLimit(), Types.INTEGER);
                return ps;
            }, keyHolder);
            item.setId(keyHolder.getKey().longValue());
        } else {
            jdbcTemplate.update(
                    "UPDATE menu_items SET name = ?, category_id = ?, photo_path = ?, price = ?, discount_price = ?, "
                            + "discount_percent = ?, is_available = ?, daily_limit = ? WHERE id = ? AND tenant_id = ?",
                    item.getName(), item.getCategoryId(), item.getPhotoPath(), item.getPrice(), item.getDiscountPrice(),
                    item.getDiscountPercent(), item.isAvailable(), item.getDailyLimit(), item.getId(),
                    item.getTenantId());
        }
        return findByIdAndTenantId(item.getId(), item.getTenantId()).orElseThrow();
    }

    @Override
    public void updateAvailability(Long id, Long tenantId, boolean available) {
        jdbcTemplate.update(
                "UPDATE menu_items SET is_available = ? WHERE id = ? AND tenant_id = ?", available, id, tenantId);
    }

    @Override
    public int markAllAvailable(Long outletId, Long tenantId) {
        return jdbcTemplate.update(
                "UPDATE menu_items SET is_available = TRUE WHERE outlet_id = ? AND tenant_id = ? "
                        + "AND is_available = FALSE",
                outletId, tenantId);
    }

    @Override
    public void delete(Long id, Long tenantId) {
        jdbcTemplate.update("DELETE FROM menu_items WHERE id = ? AND tenant_id = ?", id, tenantId);
    }
}
