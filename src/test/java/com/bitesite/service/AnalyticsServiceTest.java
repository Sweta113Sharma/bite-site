package com.bitesite.service;

import com.bitesite.config.BusinessClock;
import com.bitesite.dao.AnalyticsDao;
import com.bitesite.dto.analytics.AnalyticsFilter;
import com.bitesite.dto.analytics.AnalyticsReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock
    private AnalyticsDao analyticsDao;

    private AnalyticsService analyticsService;

    /**
     * Pinned rather than live. The service reads the business clock (IST) while a test
     * calling LocalDate.now() would read the runner's zone, so on a UTC CI machine the two
     * disagree for the first five and a half hours of every day.
     */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 10);

    @BeforeEach
    void setUp() {
        BusinessClock clock = new BusinessClock(
                Clock.fixed(Instant.parse("2026-09-10T12:49:00Z"), ZoneId.of("Asia/Kolkata")));
        analyticsService = new AnalyticsServiceImpl(analyticsDao, clock);
    }

    @Test
    void testPreset7dResolvesCorrectDateRange() {
        when(analyticsDao.fetchKpis(any())).thenReturn(new AnalyticsDao.OverallKpis(
                10L,
                new BigDecimal("1500.00"),
                new BigDecimal("150.00"),
                8L,
                2L,
                new BigDecimal("300.00"),
                new BigDecimal("50.00"),
                new BigDecimal("30.00"),
                new BigDecimal("20.00")
        ));
        when(analyticsDao.fetchRepeatCustomerRate(any())).thenReturn(25.0);
        when(analyticsDao.fetchDailyTrends(any())).thenReturn(Collections.emptyList());
        when(analyticsDao.fetchHourlyDemands(any())).thenReturn(Collections.emptyList());
        when(analyticsDao.fetchCanteenPerformances(any())).thenReturn(Collections.emptyList());
        when(analyticsDao.fetchTopSellingItems(any(), any(Integer.class))).thenReturn(Collections.emptyList());
        when(analyticsDao.fetchSlowMovingItems(any(), any(Integer.class))).thenReturn(Collections.emptyList());

        AnalyticsFilter filter = AnalyticsFilter.builder()
                .range("7d")
                .build();

        AnalyticsReport report = analyticsService.generateReport(filter);

        assertThat(report).isNotNull();
        assertThat(report.getFromDate()).isEqualTo(TODAY.minusDays(6));
        assertThat(report.getToDate()).isEqualTo(TODAY);
        assertThat(report.getTotalOrders()).isEqualTo(10L);
        assertThat(report.getGrossGmv()).isEqualByComparingTo("1500.00");
        assertThat(report.getPlatformRevenue()).isEqualByComparingTo("150.00");
        assertThat(report.getAverageOrderValue()).isEqualByComparingTo("150.00");
        assertThat(report.getRepeatCustomerRate()).isEqualTo(25.0);
        // 10 paid out of (10 paid + 2 cancelled) = 83.33%
        assertThat(report.getCompletionRate()).isBetween(83.0, 83.5);
    }

    @Test
    void testCustomRangePreservesProvidedDates() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 5);

        when(analyticsDao.fetchKpis(any())).thenReturn(new AnalyticsDao.OverallKpis(
                0L, BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        ));
        when(analyticsDao.fetchRepeatCustomerRate(any())).thenReturn(0.0);

        AnalyticsFilter filter = AnalyticsFilter.builder()
                .range("custom")
                .fromDate(from)
                .toDate(to)
                .build();

        AnalyticsReport report = analyticsService.generateReport(filter);

        assertThat(report.getFromDate()).isEqualTo(from);
        assertThat(report.getToDate()).isEqualTo(to);
        assertThat(report.getTotalOrders()).isEqualTo(0L);
        assertThat(report.getAverageOrderValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.getCompletionRate()).isEqualTo(100.0);
    }
}
