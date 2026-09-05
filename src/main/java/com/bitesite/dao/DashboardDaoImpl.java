package com.bitesite.dao;

import com.bitesite.dto.AdminInsights;
import com.bitesite.dto.PlatformSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * Counts for {@code /admin}.
 *
 * <p>Every figure is a single aggregate, and the day boundary is resolved in the database
 * via {@code token_day} — the stored generated {@code DATE(created_at)} that the order
 * token's uniqueness constraint already sits on. Computing "today" in Java would shift the
 * boundary by the server's UTC offset, which is the same trap {@code findExpiredAwaitingPayment}
 * documents.
 *
 * <p>These run on every admin page load, so they are counts over indexed predicates and
 * nothing else — no joins, no row materialisation.
 */
@Repository
@RequiredArgsConstructor
public class DashboardDaoImpl implements DashboardDao {

    /** Orders that are somebody's problem right now: paid and waiting, being cooked, or
     * cooked and not yet collected. */
    private static final String IN_FLIGHT = "('PAID','PREPARING','READY_FOR_PICKUP')";

    /** Cancelled and expired orders are excluded from revenue for the same reason
     * dailySales excludes them: money that was refunded or never taken is not takings. */
    private static final String EARNING = "('PAID','PREPARING','READY_FOR_PICKUP','COMPLETED')";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public PlatformSnapshot platformSnapshot() {
        return new PlatformSnapshot(
                count("SELECT COUNT(*) FROM orders WHERE token_day = CURDATE()"),
                money("SELECT COALESCE(SUM(total_amount), 0) FROM orders "
                        + "WHERE token_day = CURDATE() AND status IN " + EARNING),
                count("SELECT COUNT(*) FROM orders WHERE status IN " + IN_FLIGHT),
                count("SELECT COUNT(*) FROM payments WHERE status = 'FAILED' AND DATE(created_at) = CURDATE()"),
                // Money held against an order that cannot be honoured. Not time-boxed to
                // today: it stays on the screen until somebody refunds it.
                count("SELECT COUNT(*) FROM payments WHERE needs_reconciliation = TRUE"),
                count("SELECT COUNT(*) FROM grievances WHERE status <> 'RESOLVED'"),
                count("SELECT COUNT(*) FROM data_requests WHERE status IN ('OPEN','IN_PROGRESS')"),
                count("SELECT COUNT(*) FROM tenants WHERE status = 'ACTIVE'"),
                count("SELECT COUNT(*) FROM outlets WHERE is_active = TRUE"));
    }

    @Override
    public AdminInsights insights() {
        List<AdminInsights.SellOutAlert> sellOuts = jdbcTemplate.query(
                "SELECT mi.id AS menu_item_id, mi.name, o.name AS outlet_name, "
                        + "SUM(oi.quantity) AS sold_today, mi.daily_limit "
                        + "FROM order_items oi "
                        + "JOIN orders ord ON ord.id = oi.order_id "
                        + "JOIN menu_items mi ON mi.id = oi.menu_item_id "
                        + "JOIN outlets o ON o.id = ord.outlet_id "
                        + "WHERE ord.token_day = CURDATE() "
                        + "  AND ord.status IN ('PAID','PREPARING','READY_FOR_PICKUP','COMPLETED') "
                        + "GROUP BY mi.id, mi.name, o.name, mi.daily_limit "
                        + "HAVING mi.daily_limit IS NOT NULL AND SUM(oi.quantity) >= mi.daily_limit "
                        + "ORDER BY o.name, mi.name",
                (rs, i) -> new AdminInsights.SellOutAlert(
                        rs.getLong("menu_item_id"), rs.getString("name"),
                        rs.getString("outlet_name"), rs.getLong("sold_today"),
                        rs.getObject("daily_limit", Long.class)));

        List<AdminInsights.PopularItem> popularItems = jdbcTemplate.query(
                "SELECT mi.id AS menu_item_id, mi.name, o.name AS outlet_name, "
                        + "SUM(oi.quantity) AS sold_today, "
                        + "SUM(oi.quantity * oi.unit_price) AS revenue "
                        + "FROM order_items oi "
                        + "JOIN orders ord ON ord.id = oi.order_id "
                        + "JOIN menu_items mi ON mi.id = oi.menu_item_id "
                        + "JOIN outlets o ON o.id = ord.outlet_id "
                        + "WHERE ord.token_day = CURDATE() "
                        + "  AND ord.status IN ('PAID','PREPARING','READY_FOR_PICKUP','COMPLETED') "
                        + "GROUP BY mi.id, mi.name, o.name "
                        + "ORDER BY sold_today DESC, revenue DESC "
                        + "LIMIT 5",
                (rs, i) -> new AdminInsights.PopularItem(
                        rs.getLong("menu_item_id"), rs.getString("name"),
                        rs.getString("outlet_name"), rs.getLong("sold_today"),
                        rs.getBigDecimal("revenue")));

        List<AdminInsights.PeakHour> peakHours = jdbcTemplate.query(
                "SELECT HOUR(created_at) AS hour, COUNT(*) AS order_count "
                        + "FROM orders WHERE token_day = CURDATE() "
                        + "GROUP BY HOUR(created_at) ORDER BY hour",
                (rs, i) -> new AdminInsights.PeakHour(
                        rs.getInt("hour"), rs.getLong("order_count")));

        return new AdminInsights(sellOuts, popularItems, peakHours);
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private BigDecimal money(String sql) {
        BigDecimal value = jdbcTemplate.queryForObject(sql, BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }
}
