package com.bitesite.dao;

import com.bitesite.dto.PlatformSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

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

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private BigDecimal money(String sql) {
        BigDecimal value = jdbcTemplate.queryForObject(sql, BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }
}
