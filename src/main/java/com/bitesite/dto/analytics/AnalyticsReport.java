package com.bitesite.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * Aggregated analytics reporting model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsReport {

    private LocalDate fromDate;
    private LocalDate toDate;

    // --- Executive KPIs ---
    @Builder.Default
    private BigDecimal grossGmv = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal platformRevenue = BigDecimal.ZERO;

    @Builder.Default
    private long totalOrders = 0;

    @Builder.Default
    private BigDecimal averageOrderValue = BigDecimal.ZERO;

    @Builder.Default
    private long uniqueCustomers = 0;

    @Builder.Default
    private double repeatCustomerRate = 0.0;

    @Builder.Default
    private long cancelledOrders = 0;

    @Builder.Default
    private BigDecimal lostGmv = BigDecimal.ZERO;

    @Builder.Default
    private double completionRate = 100.0;

    @Builder.Default
    private BigDecimal promoDiscountsTotal = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal promoDiscountsPlatform = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal promoDiscountsCanteen = BigDecimal.ZERO;

    // --- Visual Breakdown Collections ---
    @Builder.Default
    private List<DailyTrend> dailyTrends = Collections.emptyList();

    @Builder.Default
    private List<HourlyDemand> hourlyDemands = Collections.emptyList();

    @Builder.Default
    private List<CanteenPerformance> canteenPerformances = Collections.emptyList();

    @Builder.Default
    private List<MenuItemVelocity> topSellingItems = Collections.emptyList();

    @Builder.Default
    private List<MenuItemVelocity> slowMovingItems = Collections.emptyList();

    // --- Nested records ---

    public record DailyTrend(
            LocalDate day,
            long orderCount,
            BigDecimal grossGmv,
            BigDecimal platformRevenue,
            int heightPercent) {}

    public record HourlyDemand(
            int hour,
            long orderCount,
            BigDecimal grossGmv,
            int heightPercent) {}

    public record CanteenPerformance(
            long outletId,
            String outletName,
            String collegeName,
            long orderCount,
            BigDecimal grossGmv,
            BigDecimal platformCommission,
            BigDecimal avgOrderValue,
            long cancelCount,
            double cancelRate) {}

    public record MenuItemVelocity(
            long menuItemId,
            String itemName,
            String outletName,
            long quantitySold,
            BigDecimal revenue) {}
}
