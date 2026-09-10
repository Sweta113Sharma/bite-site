package com.bitesite.dao;

import com.bitesite.model.Order;
import com.bitesite.model.OrderItem;
import com.bitesite.model.OrderStatus;
import com.bitesite.model.Settlement;
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
import java.util.ArrayList;
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
            // The terms this order was placed on. Read back for the invoice and the
            // settlement report; never recalculated from today's settings.
            .foodAmount(rs.getBigDecimal("food_amount"))
            .platformFee(rs.getBigDecimal("platform_fee"))
            .platformFeeShown(rs.getBigDecimal("platform_fee_shown"))
            .tipAmount(rs.getBigDecimal("tip_amount"))
            .commissionPercent(rs.getBigDecimal("commission_percent"))
            .commissionAmount(rs.getBigDecimal("commission_amount"))
            .gstPercent(rs.getBigDecimal("gst_percent"))
            .promoCode(rs.getString("promo_code"))
            .discountAmount(rs.getBigDecimal("discount_amount"))
            .discountFundedBy(rs.getString("discount_funded_by"))
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

    /** Falls back rather than writing a null into a NOT NULL money column. */
    private static java.math.BigDecimal orZero(java.math.BigDecimal value, java.math.BigDecimal fallback) {
        return value != null ? value : fallback;
    }

    @Override
    @Transactional
    public Order createOrder(Order order) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO orders (tenant_id, outlet_id, user_id, token_no, total_amount, status, "
                            + "food_amount, platform_fee, platform_fee_shown, tip_amount, "
                            + "commission_percent, commission_amount, gst_percent, "
                            + "promo_code, discount_amount, discount_funded_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, order.getTenantId());
            ps.setLong(2, order.getOutletId());
            ps.setLong(3, order.getUserId());
            ps.setString(4, order.getTokenNo());
            ps.setBigDecimal(5, order.getTotalAmount());
            ps.setString(6, order.getStatus().name());
            // Written once, at the only moment the terms are known to be current.
            ps.setBigDecimal(7, orZero(order.getFoodAmount(), order.getTotalAmount()));
            ps.setBigDecimal(8, orZero(order.getPlatformFee(), java.math.BigDecimal.ZERO));
            ps.setBigDecimal(9, orZero(order.getPlatformFeeShown(), java.math.BigDecimal.ZERO));
            ps.setBigDecimal(10, orZero(order.getTipAmount(), java.math.BigDecimal.ZERO));
            ps.setBigDecimal(11, order.getCommissionPercent());
            ps.setBigDecimal(12, order.getCommissionAmount());
            ps.setBigDecimal(13, order.getGstPercent());
            ps.setString(14, order.getPromoCode());
            ps.setBigDecimal(15, orZero(order.getDiscountAmount(), java.math.BigDecimal.ZERO));
            ps.setString(16, order.getDiscountFundedBy());
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
    public List<Order> findKitchenQueue(Long tenantId, Long outletId, int selfCancelWindowSeconds) {
        List<Order> orders = jdbcTemplate.query(
                "SELECT * FROM orders WHERE tenant_id = ? AND outlet_id = ? "
                        + "AND status IN ('PAID','PREPARING','READY_FOR_PICKUP') "
                        // Only PAID is held back, and only until the student's window shuts.
                        // PREPARING and READY_FOR_PICKUP are past it by definition. A PAID row
                        // with no paid_at should never exist, but if one does it is shown
                        // rather than hidden forever — a visible order the kitchen can act on
                        // beats one that silently never arrives.
                        + "AND (status <> 'PAID' OR paid_at IS NULL "
                        + "     OR TIMESTAMPDIFF(SECOND, COALESCE(cancel_window_starts_at, paid_at), NOW()) >= ?) "
                        + "ORDER BY created_at ASC",
                ORDER_ROW_MAPPER, tenantId, outletId, selfCancelWindowSeconds);
        attachItems(orders);
        return orders;
    }

    @Override
    public int selfCancelSecondsLeft(Long orderId, Long tenantId, int windowSeconds) {
        List<Integer> left = jdbcTemplate.query(
                "SELECT GREATEST(0, ? - TIMESTAMPDIFF(SECOND, "
                        + "COALESCE(cancel_window_starts_at, paid_at), NOW())) FROM orders "
                        + "WHERE id = ? AND tenant_id = ? AND status = 'PAID' AND paid_at IS NOT NULL",
                (rs, n) -> rs.getInt(1), windowSeconds, orderId, tenantId);
        // Empty for anything not currently a paid order, which is the same as no time left.
        return left.isEmpty() ? 0 : left.get(0);
    }

    @Override
    public boolean startCancelWindow(Long orderId, Long tenantId, int windowSeconds) {
        // Set once and only while the order is still hidden from the kitchen. Both
        // conditions matter. IS NULL makes a repeated callback a no-op instead of a way to
        // keep extending the window. The TIMESTAMPDIFF guard means the anchor can only ever
        // move forward while nobody has been shown the order, so an order that has already
        // surfaced on the outlet queue can never vanish off it again. A student whose
        // device comes back after the window has already elapsed is simply too late, which
        // is what they would have been anyway.
        int updated = jdbcTemplate.update(
                "UPDATE orders SET cancel_window_starts_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND tenant_id = ? AND status = 'PAID' "
                        + "AND cancel_window_starts_at IS NULL AND paid_at IS NOT NULL "
                        + "AND TIMESTAMPDIFF(SECOND, paid_at, NOW()) < ?",
                orderId, tenantId, windowSeconds);
        return updated > 0;
    }

    @Override
    public boolean isWithinSelfCancelWindow(Long orderId, Long tenantId, int windowSeconds) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE id = ? AND tenant_id = ? AND status = 'PAID' "
                        + "AND paid_at IS NOT NULL "
                        + "AND TIMESTAMPDIFF(SECOND, COALESCE(cancel_window_starts_at, paid_at), NOW()) < ?",
                Integer.class, orderId, tenantId, windowSeconds);
        return count != null && count > 0;
    }

    @Override
    public List<Order> findByUserId(Long userId, Long tenantId) {
        List<Order> orders = jdbcTemplate.query(
                "SELECT * FROM orders WHERE user_id = ? AND tenant_id = ? ORDER BY created_at DESC",
                ORDER_ROW_MAPPER, userId, tenantId);
        attachItems(orders);
        return orders;
    }

    @Override
    public List<Order> findLiveByUserId(Long userId, Long tenantId) {
        // Terminal states are excluded here rather than in Java: this runs on every
        // customer page, and a student with a long history should not pay to load it.
        // idx_orders_user covers the user_id predicate.
        List<Order> orders = jdbcTemplate.query(
                "SELECT * FROM orders WHERE user_id = ? AND tenant_id = ? "
                        + "AND status NOT IN ('COMPLETED', 'EXPIRED', 'CANCELLED') "
                        + "ORDER BY created_at DESC",
                ORDER_ROW_MAPPER, userId, tenantId);
        attachItems(orders);
        return orders;
    }

    @Override
    public List<Order> findByOutlet(Long tenantId, Long outletId, OrderStatus status, int limit, int offset) {
        // idx_orders_outlet_created (outlet_id, created_at) covers both the filter and the
        // sort. The status predicate is appended rather than always-present so the common
        // "everything" case stays a clean index range scan.
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM orders WHERE tenant_id = ? AND outlet_id = ?");
        List<Object> args = new ArrayList<>(List.of(tenantId, outletId));
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);

        List<Order> orders = jdbcTemplate.query(sql.toString(), ORDER_ROW_MAPPER, args.toArray());
        attachItems(orders);
        return orders;
    }

    @Override
    public List<DailySales> dailySales(Long tenantId, Long outletId, int days) {
        // token_day is the stored generated DATE(created_at). Grouping on it keeps the day
        // boundary in the database, where created_at is written — a Java-side date would be
        // shifted by the server's UTC offset (see findExpiredAwaitingPayment).
        return jdbcTemplate.query(
                "SELECT token_day, COUNT(*) AS order_count, COALESCE(SUM(total_amount), 0) AS revenue "
                        + "FROM orders WHERE tenant_id = ? AND outlet_id = ? "
                        + "AND status NOT IN ('CANCELLED','EXPIRED','PAYMENT_FAILED','AWAITING_PAYMENT') "
                        + "AND token_day >= CURDATE() - INTERVAL ? DAY "
                        + "GROUP BY token_day ORDER BY token_day DESC",
                (rs, n) -> new DailySales(
                        rs.getObject("token_day", java.time.LocalDate.class),
                        rs.getInt("order_count"),
                        rs.getBigDecimal("revenue")),
                tenantId, outletId, days);
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
    public List<Long> findFrequentMenuItemIds(Long userId, Long tenantId, Long outletId, int limit) {
        // AWAITING_PAYMENT is excluded alongside the failure states: an order nobody paid
        // for is not evidence anybody wanted the food, and offering it back as a
        // favourite would be a suggestion built out of an abandoned checkout.
        return jdbcTemplate.queryForList(
                "SELECT oi.menu_item_id FROM order_items oi "
                        + "JOIN orders o ON o.id = oi.order_id "
                        + "WHERE o.user_id = ? AND o.tenant_id = ? AND o.outlet_id = ? "
                        + "AND o.status NOT IN ('CANCELLED','EXPIRED','PAYMENT_FAILED','AWAITING_PAYMENT') "
                        + "GROUP BY oi.menu_item_id "
                        + "ORDER BY COUNT(*) DESC, MAX(o.created_at) DESC "
                        + "LIMIT ?",
                Long.class, userId, tenantId, outletId, limit);
    }

    @Override
    public List<Order> searchByTokenAcrossTenants(String token) {
        // Suffix match so a student can hand over the tail of a token ("1984") rather
        // than the whole thing; anchored on the right so it still uses a scan of one
        // short column rather than matching mid-string noise.
        List<Order> orders = jdbcTemplate.query(
                "SELECT * FROM orders WHERE token_no LIKE ? ORDER BY created_at DESC LIMIT 25",
                ORDER_ROW_MAPPER, "%" + token);
        // Without this the support desk showed no line items for a token search, while a
        // payment-reference search — which goes through findByIdAndTenantId — showed them.
        // Same screen, same template, two different answers depending on what was typed.
        attachItems(orders);
        return orders;
    }

    /**
     * The payout query. Groups by canteen and sums what each order recorded.
     *
     * <p>Only PAID, PREPARING, READY_FOR_PICKUP and COMPLETED count: those are the states
     * where money was taken and kept. An abandoned, expired or refunded order moved no
     * money to this canteen and must not inflate a payout.
     *
     * <p>food_amount rather than total_amount, because total includes the platform's own
     * fee and the student's tip — neither of which is the canteen's, and neither of which
     * the commission is taken on.
     */
    @Override
    public List<Settlement> settlementByOutlet(java.time.LocalDateTime from, Long tenantId) {
        StringBuilder sql = new StringBuilder("""
                SELECT o.outlet_id, ou.name AS outlet_name, o.tenant_id, t.name AS college_name,
                       COUNT(*) AS order_count,
                       -- The canteen's actual revenue. A discount IT funded really did
                       -- lower what it sold for; one the PLATFORM funded did not, and the
                       -- canteen must still be settled on the full amount.
                       COALESCE(SUM(o.food_amount - CASE WHEN o.discount_funded_by = 'CANTEEN'
                                                         THEN o.discount_amount ELSE 0 END), 0) AS food_total,
                       COALESCE(SUM(CASE WHEN o.discount_funded_by = 'PLATFORM'
                                         THEN o.discount_amount ELSE 0 END), 0) AS platform_discounts,
                       COALESCE(SUM(o.commission_amount), 0) AS commission,
                       COALESCE(SUM(o.platform_fee), 0)      AS platform_fees,
                       COALESCE(SUM(o.tip_amount), 0)        AS tips,
                       COALESCE(SUM(o.total_amount), 0)      AS collected
                FROM orders o
                JOIN outlets ou ON ou.id = o.outlet_id
                JOIN tenants t  ON t.id  = o.tenant_id
                WHERE o.status IN ('PAID','PREPARING','READY_FOR_PICKUP','COMPLETED')
                """);
        List<Object> args = new ArrayList<>();
        if (from != null) {
            sql.append(" AND o.created_at >= ?");
            args.add(from);
        }
        if (tenantId != null) {
            sql.append(" AND o.tenant_id = ?");
            args.add(tenantId);
        }
        sql.append(" GROUP BY o.outlet_id, ou.name, o.tenant_id, t.name ORDER BY food_total DESC");

        return jdbcTemplate.query(sql.toString(), (rs, i) -> {
            java.math.BigDecimal food = rs.getBigDecimal("food_total");
            java.math.BigDecimal commission = rs.getBigDecimal("commission");
            return new Settlement(
                    rs.getLong("outlet_id"),
                    rs.getString("outlet_name"),
                    rs.getLong("tenant_id"),
                    rs.getString("college_name"),
                    rs.getInt("order_count"),
                    food,
                    commission,
                    // What the platform owes: the canteen's gross less the platform's cut.
                    food.subtract(commission),
                    rs.getBigDecimal("platform_fees"),
                    rs.getBigDecimal("tips"),
                    rs.getBigDecimal("platform_discounts"),
                    rs.getBigDecimal("collected"));
        }, args.toArray());
    }

    @Override
    public List<Order> findRecentAcrossTenants(Long tenantId, OrderStatus status, String search,
            int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT o.* FROM orders o");
        List<Object> args = new ArrayList<>();
        boolean searching = search != null && !search.isBlank();
        if (searching) {
            // Joined only when there is something to search for, so the common unfiltered
            // listing stays a plain index scan over orders.
            sql.append(" JOIN users u ON u.id = o.user_id");
        }
        sql.append(" WHERE 1 = 1");
        if (tenantId != null) {
            sql.append(" AND o.tenant_id = ?");
            args.add(tenantId);
        }
        if (status != null) {
            sql.append(" AND o.status = ?");
            args.add(status.name());
        }
        if (searching) {
            // The two things a student can actually tell you over a counter: the token on
            // their screen, or the address they signed up with. Anchored prefix match on
            // the token so the index is usable; email is contains, since people paraphrase.
            sql.append(" AND (o.token_no LIKE ? OR u.email LIKE ?)");
            String term = search.trim();
            args.add(term + "%");
            args.add("%" + term + "%");
        }
        sql.append(" ORDER BY o.created_at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        List<Order> orders = jdbcTemplate.query(sql.toString(), ORDER_ROW_MAPPER, args.toArray());
        attachItems(orders);
        return orders;
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
                // PAYMENT_FAILED is swept alongside AWAITING_PAYMENT. Both mean "no money
                // arrived"; the only difference is whether the gateway said so out loud.
                // Leaving failures out meant they stayed live for ever — they surfaced a
                // permanent red banner on every customer page, and the oldest on this
                // database was two weeks old.
                "SELECT * FROM orders WHERE status IN ('AWAITING_PAYMENT', 'PAYMENT_FAILED') "
                        + "AND created_at < NOW() - INTERVAL ? MINUTE",
                ORDER_ROW_MAPPER, timeoutMinutes);
    }
}
