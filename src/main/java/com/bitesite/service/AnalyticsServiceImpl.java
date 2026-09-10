package com.bitesite.service;

import com.bitesite.dao.AnalyticsDao;
import com.bitesite.dto.analytics.AnalyticsFilter;
import com.bitesite.dto.analytics.AnalyticsReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class AnalyticsServiceImpl implements AnalyticsService {

    private final AnalyticsDao analyticsDao;

    @Override
    public AnalyticsReport generateReport(AnalyticsFilter filter) {
        if (filter == null) {
            filter = new AnalyticsFilter();
        }
        resolveDates(filter);

        AnalyticsDao.OverallKpis kpis = analyticsDao.fetchKpis(filter);
        double repeatRate = analyticsDao.fetchRepeatCustomerRate(filter);

        long paidOrders = kpis.paidOrders();
        BigDecimal grossGmv = kpis.grossGmv();

        BigDecimal aov = paidOrders > 0
                ? grossGmv.divide(BigDecimal.valueOf(paidOrders), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        long totalAttempts = paidOrders + kpis.cancelledOrders();
        double completionRate = totalAttempts > 0
                ? (paidOrders * 100.0) / totalAttempts
                : 100.0;

        return AnalyticsReport.builder()
                .fromDate(filter.getFromDate())
                .toDate(filter.getToDate())
                .grossGmv(grossGmv)
                .platformRevenue(kpis.platformRevenue())
                .totalOrders(paidOrders)
                .averageOrderValue(aov)
                .uniqueCustomers(kpis.uniqueCustomers())
                .repeatCustomerRate(repeatRate)
                .cancelledOrders(kpis.cancelledOrders())
                .lostGmv(kpis.lostGmv())
                .completionRate(completionRate)
                .promoDiscountsTotal(kpis.promoDiscountsTotal())
                .promoDiscountsPlatform(kpis.promoDiscountsPlatform())
                .promoDiscountsCanteen(kpis.promoDiscountsCanteen())
                .dailyTrends(analyticsDao.fetchDailyTrends(filter))
                .hourlyDemands(analyticsDao.fetchHourlyDemands(filter))
                .canteenPerformances(analyticsDao.fetchCanteenPerformances(filter))
                .topSellingItems(analyticsDao.fetchTopSellingItems(filter, 10))
                .slowMovingItems(analyticsDao.fetchSlowMovingItems(filter, 10))
                .build();
    }

    private void resolveDates(AnalyticsFilter filter) {
        LocalDate today = LocalDate.now();
        String preset = filter.getRange();
        if (preset == null || preset.isBlank()) {
            preset = "7d";
            filter.setRange("7d");
        }

        switch (preset.toLowerCase()) {
            case "today" -> {
                filter.setFromDate(today);
                filter.setToDate(today);
            }
            case "yesterday" -> {
                LocalDate yesterday = today.minusDays(1);
                filter.setFromDate(yesterday);
                filter.setToDate(yesterday);
            }
            case "30d" -> {
                filter.setFromDate(today.minusDays(29));
                filter.setToDate(today);
            }
            case "mtd" -> {
                filter.setFromDate(today.withDayOfMonth(1));
                filter.setToDate(today);
            }
            case "custom" -> {
                if (filter.getFromDate() == null) {
                    filter.setFromDate(today.minusDays(6));
                }
                if (filter.getToDate() == null) {
                    filter.setToDate(today);
                }
                if (filter.getFromDate().isAfter(filter.getToDate())) {
                    LocalDate tmp = filter.getFromDate();
                    filter.setFromDate(filter.getToDate());
                    filter.setToDate(tmp);
                }
            }
            case "7d" -> {
                filter.setFromDate(today.minusDays(6));
                filter.setToDate(today);
            }
            default -> {
                filter.setRange("7d");
                filter.setFromDate(today.minusDays(6));
                filter.setToDate(today);
            }
        }
    }
}
