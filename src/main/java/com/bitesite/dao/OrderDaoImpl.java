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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            .createdAt(rs.getObject("created_at", LocalDateTime.class))
            .paidAt(rs.getObject("paid_at", LocalDateTime.class))
            .readyAt(rs.getObject("ready_at", LocalDateTime.class))
            .completedAt(rs.getObject("completed_at", LocalDateTime.class))
            .cancelledAt(rs.getObject("cancelled_at", LocalDateTime.class))
            .cancellationReason(rs.getString("cancellation_reason"))
            .pickupCode(rs.getString("pickup_code"))
            .pickupCodeIssuedAt(rs.getObject("pickup_code_issued_at", LocalDateTime.class))
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
    public void cancel(Long id, Long tenantId, String reason) {
        jdbcTemplate.update(
                "UPDATE orders SET status = ?, cancelled_at = CURRENT_TIMESTAMP, cancellation_reason = ? "
                        + "WHERE id = ? AND tenant_id = ?",
                OrderStatus.CANCELLED.name(), reason, id, tenantId);
    }

    @Override
    public void setPickupCode(Long id, Long tenantId, String code) {
        jdbcTemplate.update(
                "UPDATE orders SET pickup_code = ?, pickup_code_issued_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND tenant_id = ?",
                code, id, tenantId);
    }

    @Override
    public List<String> findActivePickupCodes(Long tenantId, Long outletId) {
        return jdbcTemplate.queryForList(
                "SELECT pickup_code FROM orders WHERE tenant_id = ? AND outlet_id = ? "
                        + "AND status = 'READY_FOR_PICKUP' AND pickup_code IS NOT NULL",
                String.class, tenantId, outletId);
    }

    @Override
    public Map<Long, Integer> sumQuantitiesByMenuItemToday(Long tenantId, Long outletId) {
        Map<Long, Integer> totals = new HashMap<>();
        jdbcTemplate.query(
                "SELECT oi.menu_item_id AS menu_item_id, SUM(oi.quantity) AS qty "
                        + "FROM order_items oi JOIN orders o ON o.id = oi.order_id "
                        + "WHERE o.tenant_id = ? AND o.outlet_id = ? AND o.created_at >= CURDATE() "
                        + "AND o.status NOT IN ('CANCELLED','EXPIRED','PAYMENT_FAILED') "
                        + "GROUP BY oi.menu_item_id",
                rs -> { totals.put(rs.getLong("menu_item_id"), rs.getInt("qty")); },
                tenantId, outletId);
        return totals;
    }

    @Override
    public List<Order> searchByTokenAcrossTenants(String token) {
        // Suffix match so a student can hand over the tail of a token ("1984") rather
        // than the whole thing; anchored on the right so it still uses a scan of one
        // short column rather than matching mid-string noise.
        return jdbcTemplate.query(
                "SELECT * FROM orders WHERE token_no LIKE ? ORDER BY created_at DESC LIMIT 25",
                ORDER_ROW_MAPPER, "%" + token);
    }

    @Override
    public boolean existsTokenForTenantToday(Long tenantId, String token) {
        // token_day is the generated DATE(created_at) the uniqueness constraint sits on, so
        // the check and the constraint agree by construction, and both are resolved by the
        // database rather than against a Java-side clock.
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND token_day = CURDATE() AND token_no = ?",
                Integer.class, tenantId, token);
        return count != null && count > 0;
    }

    @Override
    public List<Order> findExpiredAwaitingPayment(int timeoutMinutes) {
        // NOW() - INTERVAL rather than a cutoff computed in Java, for the same reason
        // sumQuantitiesByMenuItemToday() uses CURDATE(): created_at is written by the
        // database, so the value it is compared against has to come from there too. With a
        // Java-side Timestamp parameter the driver shifted the cutoff by the server's UTC
        // offset, and a 10-minute timeout behaved as 5h40m against a database running in IST.
        return jdbcTemplate.query(
                "SELECT * FROM orders WHERE status = 'AWAITING_PAYMENT' "
                        + "AND created_at < NOW() - INTERVAL ? MINUTE",
                ORDER_ROW_MAPPER, timeoutMinutes);
    }
}
