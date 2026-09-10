package com.bitesite.dao;

import com.bitesite.dto.analytics.AnalyticsFilter;
import com.bitesite.dto.analytics.AnalyticsReport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface AnalyticsDao {

    record OverallKpis(
            long paidOrders,
            BigDecimal grossGmv,
            BigDecimal platformRevenue,
            long uniqueCustomers,
            long cancelledOrders,
            BigDecimal lostGmv,
            BigDecimal promoDiscountsTotal,
            BigDecimal promoDiscountsPlatform,
            BigDecimal promoDiscountsCanteen) {}

    OverallKpis fetchKpis(AnalyticsFilter filter);

    double fetchRepeatCustomerRate(AnalyticsFilter filter);

    List<AnalyticsReport.DailyTrend> fetchDailyTrends(AnalyticsFilter filter);

    List<AnalyticsReport.HourlyDemand> fetchHourlyDemands(AnalyticsFilter filter);

    List<AnalyticsReport.CanteenPerformance> fetchCanteenPerformances(AnalyticsFilter filter);

    List<AnalyticsReport.MenuItemVelocity> fetchTopSellingItems(AnalyticsFilter filter, int limit);

    List<AnalyticsReport.MenuItemVelocity> fetchSlowMovingItems(AnalyticsFilter filter, int limit);
}
