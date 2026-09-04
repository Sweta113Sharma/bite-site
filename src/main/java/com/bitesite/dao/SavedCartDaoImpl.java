package com.bitesite.dao;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class SavedCartDaoImpl implements SavedCartDao {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Long findOutletId(Long userId) {
        return jdbcTemplate.query("SELECT outlet_id FROM saved_carts WHERE user_id = ?",
                rs -> rs.next() ? rs.getLong("outlet_id") : null, userId);
    }

    @Override
    public Map<Long, Integer> findItems(Long userId) {
        Map<Long, Integer> items = new HashMap<>();
        jdbcTemplate.query("SELECT menu_item_id, quantity FROM saved_cart_items WHERE user_id = ?",
                rs -> { items.put(rs.getLong("menu_item_id"), rs.getInt("quantity")); }, userId);
        return items;
    }

    @Override
    @Transactional
    public void save(Long userId, Long outletId, Map<Long, Integer> quantities) {
        if (quantities.isEmpty()) {
            clear(userId);
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO saved_carts (user_id, outlet_id) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE outlet_id = VALUES(outlet_id)",
                userId, outletId);
        // Full rewrite: the parent row must exist first, so this cannot be reordered.
        jdbcTemplate.update("DELETE FROM saved_cart_items WHERE user_id = ?", userId);
        jdbcTemplate.batchUpdate(
                "INSERT INTO saved_cart_items (user_id, menu_item_id, quantity) VALUES (?, ?, ?)",
                quantities.entrySet().stream()
                        .map(e -> new Object[]{userId, e.getKey(), e.getValue()})
                        .toList());
    }

    @Override
    @Transactional
    public void clear(Long userId) {
        // Items cascade from the parent, so one delete is enough.
        jdbcTemplate.update("DELETE FROM saved_carts WHERE user_id = ?", userId);
    }
}
