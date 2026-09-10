package com.bitesite.dao;

import com.bitesite.dto.analytics.AnalyticsFilter;
import com.bitesite.dto.analytics.AnalyticsReport;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class AnalyticsDaoImpl implements AnalyticsDao {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public OverallKpis fetchKpis(AnalyticsFilter filter) {
        StringBuilder sql = new StringBuilder("""
            SELECT
              COUNT(CASE WHEN status IN ('PAID','PREPARING','READY_FOR_PICKUP','COMPLETED') THEN 1 END) AS paid_orders,
              COALESCE(SUM(CASE WHEN status IN ('PAID','PREPARING','READY_FOR_PICKUP','COMPLETED') THEN food_amount ELSE 0 END), 0) AS gross_gmv,
              COALESCE(SUM(CASE WHEN status IN ('PAID','PREPARING','READY_FOR_PICKUP','COMPLETED') THEN COALESCE(commission_amount, 0) + COALESCE(platform_fee, 0) ELSE 0 END), 0) AS platform_revenue,
              COUNT(DISTINCT CASE WHEN status IN ('PAID','PREPARING','READY_FOR_PICKUP','COMPLETED') THEN user_id END) AS unique_customers,
              COUNT(CASE WHEN status IN ('CANCELLED','EXPIRED') THEN 1 END) AS cancelled_orders,
              COALESCE(SUM(CASE WHEN status IN ('CANCELLED','EXPIRED') THEN total_amount ELSE 0 END), 0) AS lost_gmv,
              COALESCE(SUM(CASE WHEN status IN ('PAID','PREPARING','READY_FOR_PICKUP','COMPLETED') THEN discount_amount ELSE 0 END), 0) AS promo_total,
              COALESCE(SUM(CASE WHEN status IN ('PAID','PREPARING','READY_FOR_PICKUP','COMPLETED') AND discount_funded_by = 'PLATFORM' THEN discount_amount ELSE 0 END), 0) AS promo_platform,
              COALESCE(SUM(CASE WHEN status IN ('PAID','PREPARING','READY_FOR_PICKUP','COMPLETED') AND discount_funded_by = 'CANTEEN' THEN discount_amount ELSE 0 END), 0) AS promo_canteen
            FROM orders
            WHERE token_day >= ? AND token_day <= ?
        """);

        List<Object> args = new ArrayList<>();
        args.add(filter.getFromDate());
        args.add(filter.getToDate());

        if (filter.getTenantId() != null) {
            sql.append(" AND tenant_id = ?");
            args.add(filter.getTenantId());
        }
        if (filter.getOutletId() != null) {
            sql.append(" AND outlet_id = ?");
            args.add(filter.getOutletId());
        }

        return jdbcTemplate.queryForObject(sql.toString(), (rs, rowNum) -> new OverallKpis(
                rs.getLong("paid_orders"),
                rs.getBigDecimal("gross_gmv"),
                rs.getBigDecimal("platform_revenue"),
                rs.getLong("unique_customers"),
                rs.getLong("cancelled_orders"),
                rs.getBigDecimal("lost_gmv"),
                rs.getBigDecimal("promo_total"),
                rs.getBigDecimal("promo_platform"),
                rs.getBigDecimal("promo_canteen")
        ), args.toArray());
    }

    @Override
    public double fetchRepeatCustomerRate(AnalyticsFilter filter) {
        StringBuilder sql = new StringBuilder("""
            SELECT
              COUNT(DISTINCT user_id) AS total_users,
              COUNT(DISTINCT CASE WHEN order_count >= 2 THEN user_id END) AS repeat_users
            FROM (
              SELECT user_id, COUNT(*) AS order_count
              FROM orders
              WHERE token_day >= ? AND token_day <= ?
                AND status IN ('PAID','PREPARING','READY_FOR_PICKUP','COMPLETED')
        """);

        List<Object> args = new ArrayList<>();
        args.add(filter.getFromDate());
        args.add(filter.getToDate());

        if (filter.getTenantId() != null) {
            sql.append(" AND tenant_id = ?");
            args.add(filter.getTenantId());
        }
        if (filter.getOutletId() != null) {
            sql.append(" AND outlet_id = ?");
            args.add(filter.getOutletId());
        }

        sql.append(" GROUP BY user_id) sub");

        return jdbcTemplate.queryForObject(sql.toString(), (rs, rowNum) -> {
            long totalUsers = rs.getLong("total_users");
            long repeatUsers = rs.getLong("repeat_users");
            if (totalUsers == 0) {
                return 0.0;
            }
            return (repeatUsers * 100.0) / totalUsers;
        }, args.toArray());
    }

    @Override
    public List<AnalyticsReport.DailyTrend> fetchDailyTrends(AnalyticsFilter filter) {
        StringBuilder sql = new StringBuilder("""
            SELECT
              token_day,
              COUNT(*) AS order_count,
              COALESCE(SUM(food_amount), 0) AS gross_gmv,
              COALESCE(SUM(COALESCE(commission_amount, 0) + COALESCE(platform_fee, 0)), 0) AS platform_revenue
            FROM orders
            WHERE token_day >= ? AND token_day <= ?
              AND status IN ('PAID','PREPARING','READY_FOR_PICKUP','COMPLETED')
        """);

        List<Object> args = new ArrayList<>();
        args.add(filter.getFromDate());
        args.add(filter.getToDate());

        if (filter.getTenantId() != null) {
            sql.append(" AND tenant_id = ?");
            args.add(filter.getTenantId());
        }
        if (filter.getOutletId() != null) {
            sql.append(" AND outlet_id = ?");
            args.add(filter.getOutletId());
        }

        sql.append(" GROUP BY token_day ORDER BY token_day ASC");

        List<AnalyticsReport.DailyTrend> list = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new AnalyticsReport.DailyTrend(
                rs.getObject("token_day", LocalDate.class),
                rs.getLong("order_count"),
                rs.getBigDecimal("gross_gmv"),
                rs.getBigDecimal("platform_revenue"),
                0
        ), args.toArray());

        if (list.isEmpty()) {
            return list;
        }

        BigDecimal maxGmv = list.stream()
                .map(AnalyticsReport.DailyTrend::grossGmv)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        List<AnalyticsReport.DailyTrend> result = new ArrayList<>(list.size());
        for (AnalyticsReport.DailyTrend item : list) {
            int heightPercent = (maxGmv.compareTo(BigDecimal.ZERO) > 0)
                    ? item.grossGmv().multiply(new BigDecimal(100)).divide(maxGmv, 0, RoundingMode.HALF_UP).intValue()
                    : 10;
            result.add(new AnalyticsReport.DailyTrend(
                    item.day(),
                    item.orderCount(),
                    item.grossGmv(),
                    item.platformRevenue(),
                    Math.max(heightPercent, 8)
            ));
        }
        return result;
    }

    @Override
    public List<AnalyticsReport.HourlyDemand> fetchHourlyDemands(AnalyticsFilter filter) {
        StringBuilder sql = new StringBuilder("""
            SELECT
              HOUR(created_at) AS hr,
              COUNT(*) AS order_count,
              COALESCE(SUM(food_amount), 0) AS gross_gmv
            FROM orders
            WHERE token_day >= ? AND token_day <= ?
              AND status IN ('PAID','PREPARING','READY_FOR_PICKUP','COMPLETED')
        """);

        List<Object> args = new ArrayList<>();
        args.add(filter.getFromDate());
        args.add(filter.getToDate());

        if (filter.getTenantId() != null) {
            sql.append(" AND tenant_id = ?");
            args.add(filter.getTenantId());
        }
        if (filter.getOutletId() != null) {
            sql.append(" AND outlet_id = ?");
            args.add(filter.getOutletId());
        }

        sql.append(" GROUP BY HOUR(created_at) ORDER BY hr ASC");

        List<AnalyticsReport.HourlyDemand> dbRows = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new AnalyticsReport.HourlyDemand(
                rs.getInt("hr"),
                rs.getLong("order_count"),
                rs.getBigDecimal("gross_gmv"),
                0
        ), args.toArray());

        long maxOrders = dbRows.stream()
                .mapToLong(AnalyticsReport.HourlyDemand::orderCount)
                .max()
                .orElse(0L);

        // Map all standard canteen operational hours (8am to 22pm) or 24h
        List<AnalyticsReport.HourlyDemand> result = new ArrayList<>();
        for (int h = 8; h <= 21; h++) {
            final int currentHour = h;
            AnalyticsReport.HourlyDemand matched = dbRows.stream()
                    .filter(d -> d.hour() == currentHour)
                    .findFirst()
                    .orElse(new AnalyticsReport.HourlyDemand(currentHour, 0, BigDecimal.ZERO, 0));

            int heightPercent = (maxOrders > 0)
                    ? (int) ((matched.orderCount() * 100) / maxOrders)
                    : 6;

            result.add(new AnalyticsReport.HourlyDemand(
                    currentHour,
                    matched.orderCount(),
                    matched.grossGmv(),
                    Math.max(heightPercent, 6)
            ));
        }
        return result;
    }

    @Override
    public List<AnalyticsReport.CanteenPerformance> fetchCanteenPerformances(AnalyticsFilter filter) {
        StringBuilder sql = new StringBuilder("""
            SELECT
              o.id AS outlet_id,
              o.name AS outlet_name,
              t.name AS college_name,
              COUNT(CASE WHEN ord.status IN ('PAID','PREPARING','READY_FOR_PICKUP','COMPLETED') THEN 1 END) AS order_count,
              COALESCE(SUM(CASE WHEN ord.status IN ('PAID','PREPARING','READY_FOR_PICKUP','COMPLETED') THEN ord.food_amount ELSE 0 END), 0) AS gross_gmv,
              COALESCE(SUM(CASE WHEN ord.status IN ('PAID','PREPARING','READY_FOR_PICKUP','COMPLETED') THEN COALESCE(ord.commission_amount, 0) ELSE 0 END), 0) AS commission_cut,
              COUNT(CASE WHEN ord.status IN ('CANCELLED','EXPIRED') THEN 1 END) AS cancel_count
            FROM outlets o
            JOIN tenants t ON o.tenant_id = t.id
            LEFT JOIN orders ord ON ord.outlet_id = o.id
              AND ord.token_day >= ? AND ord.token_day <= ?
            WHERE 1=1
        """);

        List<Object> args = new ArrayList<>();
        args.add(filter.getFromDate());
        args.add(filter.getToDate());

        if (filter.getTenantId() != null) {
            sql.append(" AND o.tenant_id = ?");
            args.add(filter.getTenantId());
        }
        if (filter.getOutletId() != null) {
            sql.append(" AND o.id = ?");
            args.add(filter.getOutletId());
        }

        sql.append(" GROUP BY o.id, o.name, t.name HAVING order_count > 0 OR cancel_count > 0 ORDER BY gross_gmv DESC, order_count DESC");

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            long orders = rs.getLong("order_count");
            BigDecimal gmv = rs.getBigDecimal("gross_gmv");
            BigDecimal commission = rs.getBigDecimal("commission_cut");
            long cancels = rs.getLong("cancel_count");

            BigDecimal aov = orders > 0
                    ? gmv.divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            long totalAttempts = orders + cancels;
            double cancelRate = totalAttempts > 0
                    ? (cancels * 100.0) / totalAttempts
                    : 0.0;

            return new AnalyticsReport.CanteenPerformance(
                    rs.getLong("outlet_id"),
                    rs.getString("outlet_name"),
                    rs.getString("college_name"),
                    orders,
                    gmv,
                    commission,
                    aov,
                    cancels,
                    cancelRate
            );
        }, args.toArray());
    }

    @Override
    public List<AnalyticsReport.MenuItemVelocity> fetchTopSellingItems(AnalyticsFilter filter, int limit) {
        return fetchItemVelocity(filter, "ORDER BY qty_sold DESC, revenue DESC", limit);
    }

    @Override
    public List<AnalyticsReport.MenuItemVelocity> fetchSlowMovingItems(AnalyticsFilter filter, int limit) {
        return fetchItemVelocity(filter, "ORDER BY qty_sold ASC, revenue ASC", limit);
    }

    private List<AnalyticsReport.MenuItemVelocity> fetchItemVelocity(AnalyticsFilter filter, String orderClause, int limit) {
        StringBuilder sql = new StringBuilder("""
            SELECT
              oi.menu_item_id,
              oi.item_name_snapshot AS item_name,
              o.name AS outlet_name,
              SUM(oi.quantity) AS qty_sold,
              SUM(oi.subtotal) AS revenue
            FROM order_items oi
            JOIN orders ord ON oi.order_id = ord.id
            JOIN outlets o ON ord.outlet_id = o.id
            WHERE ord.token_day >= ? AND ord.token_day <= ?
              AND ord.status IN ('PAID','PREPARING','READY_FOR_PICKUP','COMPLETED')
        """);

        List<Object> args = new ArrayList<>();
        args.add(filter.getFromDate());
        args.add(filter.getToDate());

        if (filter.getTenantId() != null) {
            sql.append(" AND ord.tenant_id = ?");
            args.add(filter.getTenantId());
        }
        if (filter.getOutletId() != null) {
            sql.append(" AND ord.outlet_id = ?");
            args.add(filter.getOutletId());
        }

        sql.append(" GROUP BY oi.menu_item_id, oi.item_name_snapshot, o.name ");
        sql.append(orderClause);
        sql.append(" LIMIT ?");
        args.add(limit);

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new AnalyticsReport.MenuItemVelocity(
                rs.getLong("menu_item_id"),
                rs.getString("item_name"),
                rs.getString("outlet_name"),
                rs.getLong("qty_sold"),
                rs.getBigDecimal("revenue")
        ), args.toArray());
    }
}
