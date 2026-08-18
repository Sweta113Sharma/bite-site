package com.bitesite.dao;

import com.bitesite.model.Order;
import com.bitesite.model.OrderItem;
import com.bitesite.model.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class OrderDaoImpl implements OrderDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<Order> ORDER_ROW_MAPPER = (rs, rowNum) -> Order.builder()
            .id(rs.getLong("id"))
            .tenantId(rs.getLong("tenant_id"))
            .outletId(rs.getLong("outlet_id"))
            .userId(rs.getLong("user_id"))
            .tokenNo(rs.getString("token_no"))
            .totalAmount(rs.getBigDecimal("total_amount"))
            .status(OrderStatus.valueOf(rs.getString("status")))
            .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
            .paidAt(toLocalDateTime(rs.getTimestamp("paid_at")))
            .readyAt(toLocalDateTime(rs.getTimestamp("ready_at")))
            .completedAt(toLocalDateTime(rs.getTimestamp("completed_at")))
            .build();

    private static final RowMapper<OrderItem> ITEM_ROW_MAPPER = (rs, rowNum) -> OrderItem.builder()
            .id(rs.getLong("id"))
            .orderId(rs.getLong("order_id"))
            .menuItemId(rs.getLong("menu_item_id"))
            .itemNameSnapshot(rs.getString("item_name_snapshot"))
            .quantity(rs.getInt("quantity"))
            .unitPrice(rs.getBigDecimal("unit_price"))
            .subtotal(rs.getBigDecimal("subtotal"))
            .build();

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    @Override
    @Transactional
    public Order createOrder(Order order) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO orders (tenant_id, outlet_id, user_id, token_no, total_amount, status) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, order.getTenantId());
            ps.setLong(2, order.getOutletId());
            ps.setLong(3, order.getUserId());
            ps.setString(4, order.getTokenNo());
            ps.setBigDecimal(5, order.getTotalAmount());
            ps.setString(6, order.getStatus().name());
            return ps;
        }, keyHolder);
        long orderId = keyHolder.getKey().longValue();
        order.setId(orderId);

        for (OrderItem item : order.getItems()) {
            jdbcTemplate.update(
                    "INSERT INTO order_items (order_id, menu_item_id, item_name_snapshot, quantity, unit_price, subtotal) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    orderId, item.getMenuItemId(), item.getItemNameSnapshot(), item.getQuantity(),
                    item.getUnitPrice(), item.getSubtotal());
        }
        return findByIdAndTenantId(orderId, order.getTenantId()).orElseThrow();
    }

    @Override
    public Optional<Order> findByIdAndTenantId(Long id, Long tenantId) {
        Optional<Order> orderOpt = jdbcTemplate.query(
                "SELECT * FROM orders WHERE id = ? AND tenant_id = ?", ORDER_ROW_MAPPER, id, tenantId)
                .stream().findFirst();
        orderOpt.ifPresent(order -> order.setItems(
                jdbcTemplate.query("SELECT * FROM order_items WHERE order_id = ?", ITEM_ROW_MAPPER, order.getId())));
        return orderOpt;
    }

    @Override
    public List<Order> findKitchenQueue(Long tenantId, Long outletId) {
        List<Order> orders = jdbcTemplate.query(
                "SELECT * FROM orders WHERE tenant_id = ? AND outlet_id = ? "
                        + "AND status IN ('PAID','PREPARING','READY_FOR_PICKUP') ORDER BY created_at ASC",
                ORDER_ROW_MAPPER, tenantId, outletId);
        attachItems(orders);
        return orders;
    }

    @Override
    public List<Order> findByUserId(Long userId, Long tenantId) {
        List<Order> orders = jdbcTemplate.query(
                "SELECT * FROM orders WHERE user_id = ? AND tenant_id = ? ORDER BY created_at DESC",
                ORDER_ROW_MAPPER, userId, tenantId);
        attachItems(orders);
        return orders;
    }

    private void attachItems(List<Order> orders) {
        for (Order order : orders) {
            order.setItems(
                    jdbcTemplate.query("SELECT * FROM order_items WHERE order_id = ?", ITEM_ROW_MAPPER, order.getId()));
        }
    }

    @Override
    public void updateStatus(Long id, Long tenantId, OrderStatus status) {
        String timestampColumn = switch (status) {
            case PAID -> "paid_at";
            case READY_FOR_PICKUP -> "ready_at";
            case COMPLETED -> "completed_at";
            default -> null;
        };
        if (timestampColumn != null) {
            jdbcTemplate.update(
                    "UPDATE orders SET status = ?, " + timestampColumn + " = CURRENT_TIMESTAMP "
                            + "WHERE id = ? AND tenant_id = ?",
                    status.name(), id, tenantId);
        } else {
            jdbcTemplate.update(
                    "UPDATE orders SET status = ? WHERE id = ? AND tenant_id = ?", status.name(), id, tenantId);
        }
    }

    @Override
    public boolean existsTokenForTenant(Long tenantId, String token) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND token_no = ?", Integer.class, tenantId, token);
        return count != null && count > 0;
    }

    @Override
    public List<Order> findExpiredAwaitingPayment(LocalDateTime cutoff) {
        return jdbcTemplate.query(
                "SELECT * FROM orders WHERE status = 'AWAITING_PAYMENT' AND created_at < ?",
                ORDER_ROW_MAPPER, Timestamp.valueOf(cutoff));
    }
}
