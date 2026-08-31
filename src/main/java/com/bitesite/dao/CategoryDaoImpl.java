package com.bitesite.dao;

import com.bitesite.model.Category;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class CategoryDaoImpl implements CategoryDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<Category> ROW_MAPPER = (rs, rowNum) -> Category.builder()
            .id(rs.getLong("id"))
            .tenantId(rs.getLong("tenant_id"))
            .outletId(rs.getLong("outlet_id"))
            .name(rs.getString("name"))
            .sortOrder(rs.getInt("sort_order"))
            .createdAt(rs.getObject("created_at", LocalDateTime.class))
            .build();

    @Override
    public List<Category> findByOutlet(Long outletId, Long tenantId) {
        // Item counts come back with the list rather than one query per row: the management
        // screen shows them on every line, and it is the only thing that makes "delete" a
        // decision the manager can actually make.
        return jdbcTemplate.query(
                "SELECT c.*, (SELECT COUNT(*) FROM menu_items mi WHERE mi.category_id = c.id) AS item_count "
                        + "FROM categories c WHERE c.outlet_id = ? AND c.tenant_id = ? "
                        + "ORDER BY c.sort_order, c.name",
                (rs, n) -> {
                    Category c = ROW_MAPPER.mapRow(rs, n);
                    c.setItemCount(rs.getInt("item_count"));
                    return c;
                },
                outletId, tenantId);
    }

    @Override
    public Optional<Category> findByIdAndTenantId(Long id, Long tenantId) {
        return jdbcTemplate.query("SELECT * FROM categories WHERE id = ? AND tenant_id = ?",
                ROW_MAPPER, id, tenantId).stream().findFirst();
    }

    @Override
    public Category save(Category category) {
        if (category.getId() == null) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO categories (tenant_id, outlet_id, name, sort_order) VALUES (?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, category.getTenantId());
                ps.setLong(2, category.getOutletId());
                ps.setString(3, category.getName());
                ps.setInt(4, category.getSortOrder());
                return ps;
            }, keyHolder);
            category.setId(keyHolder.getKey().longValue());
        } else {
            // outlet_id is never updated: moving a category between canteens would take its
            // items with it, which is not something any screen offers.
            jdbcTemplate.update(
                    "UPDATE categories SET name = ?, sort_order = ? WHERE id = ? AND tenant_id = ?",
                    category.getName(), category.getSortOrder(), category.getId(), category.getTenantId());
        }
        return findByIdAndTenantId(category.getId(), category.getTenantId()).orElseThrow();
    }

    @Override
    public int countItems(Long categoryId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM menu_items WHERE category_id = ?", Integer.class, categoryId);
        return n == null ? 0 : n;
    }

    @Override
    public void delete(Long id, Long tenantId) {
        jdbcTemplate.update("DELETE FROM categories WHERE id = ? AND tenant_id = ?", id, tenantId);
    }
}
