package com.bitesite.dao;

import com.bitesite.dto.analytics.AnalyticsFilter;
import com.bitesite.model.Order;
import com.bitesite.model.OrderStatus;
import com.bitesite.model.Outlet;
import com.bitesite.model.Role;
import com.bitesite.model.Settlement;
import com.bitesite.model.User;
import com.bitesite.tenant.Tenant;
import com.bitesite.tenant.TenantDao;
import com.bitesite.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orders the Play review account places take no money (Order.noCharge). Before V38 they
 * were indistinguishable from real orders once placed, so every one counted as revenue on
 * the admin dashboard, as GMV in analytics, as sales on the canteen's report and as money
 * owed to the canteen in settlement.
 *
 * <p>Each tenant here holds one real order worth ₹100 and one no-charge order worth ₹500,
 * against a real database, so any figure that still counts the review order is off by
 * ₹500 and cannot pass by accident.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NoChargeOrderMoneyTest {

    @Autowired private TenantDao tenantDao;
    @Autowired private OutletDao outletDao;
    @Autowired private UserDao userDao;
    @Autowired private OrderDao orderDao;
    @Autowired private AnalyticsDao analyticsDao;
    @Autowired private DashboardDao dashboardDao;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long tenantId;
    private Long outletId;
    private Long studentId;
    /** A second college, so the backfill test's extra orders cannot change the figures above. */
    private Long backfillTenantId;
    private Long backfillOutletId;
    private Long backfillStudentId;
    private Long reviewOrderId;

    @BeforeAll
    void seed() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        tenantId = tenantDao.save(Tenant.builder().name("No Charge College " + runId)
                .status(TenantStatus.ACTIVE).build()).getId();
        outletId = outletDao.save(Outlet.builder().tenantId(tenantId)
                .name("No Charge Canteen " + runId).active(true).build()).getId();
        studentId = userDao.save(User.builder().tenantId(tenantId).name("No Charge Student")
                .email("no-charge-" + runId + "@test.local").passwordHash("x")
                .role(Role.USER).activeRole(Role.USER).active(true).build()).getId();

        backfillTenantId = tenantDao.save(Tenant.builder().name("No Charge Backfill " + runId)
                .status(TenantStatus.ACTIVE).build()).getId();
        backfillOutletId = outletDao.save(Outlet.builder().tenantId(backfillTenantId)
                .name("No Charge Backfill Canteen " + runId).active(true).build()).getId();
        backfillStudentId = userDao.save(User.builder().tenantId(backfillTenantId).name("Backfill Student")
                .email("no-charge-backfill-" + runId + "@test.local").passwordHash("x")
                .role(Role.USER).activeRole(Role.USER).active(true).build()).getId();

        completedOrder("NC-R-" + runId, "100.00", false);
        reviewOrderId = completedOrder("NC-V-" + runId, "500.00", true);
    }

    private Long completedOrder(String token, String amount, boolean noCharge) {
        return completedOrder(tenantId, outletId, studentId, token, amount, noCharge);
    }

    private Long completedOrder(Long tenant, Long outlet, Long student, String token, String amount,
            boolean noCharge) {
        Order order = orderDao.createOrder(Order.builder()
                .tenantId(tenant).outletId(outlet).userId(student).tokenNo(token)
                .totalAmount(new BigDecimal(amount)).foodAmount(new BigDecimal(amount))
                .commissionAmount(new BigDecimal(amount).divide(BigDecimal.TEN))
                .noCharge(noCharge)
                .status(OrderStatus.AWAITING_PAYMENT).items(List.of()).build());
        orderDao.updateStatus(order.getId(), tenant, OrderStatus.COMPLETED);
        return order.getId();
    }

    @Test
    void theFlagIsWrittenAtCheckoutAndReadBack() {
        assertThat(orderDao.findByIdAndTenantId(reviewOrderId, tenantId).orElseThrow().isNoCharge()).isTrue();
    }

    @Test
    void settlementOwesTheCanteenNothingForAReviewOrder() {
        Settlement settlement = orderDao.settlementByOutlet(null, tenantId).stream()
                .filter(s -> s.outletId().equals(outletId)).findFirst().orElseThrow();

        assertThat(settlement.orderCount()).isEqualTo(1);
        assertThat(settlement.foodTotal()).isEqualByComparingTo("100.00");
        assertThat(settlement.commission()).isEqualByComparingTo("10.00");
        assertThat(settlement.collected()).isEqualByComparingTo("100.00");
    }

    @Test
    void theCanteenSalesReportLeavesItOut() {
        List<OrderDao.DailySales> sales = orderDao.dailySales(tenantId, outletId, 1);

        assertThat(sales).hasSize(1);
        assertThat(sales.get(0).orderCount()).isEqualTo(1);
        assertThat(sales.get(0).revenue()).isEqualByComparingTo("100.00");
    }

    @Test
    void analyticsCountOnlyTheOrderThatTookMoney() {
        AnalyticsFilter filter = AnalyticsFilter.builder()
                .fromDate(LocalDate.now().minusDays(1)).toDate(LocalDate.now().plusDays(1))
                .tenantId(tenantId).build();

        AnalyticsDao.OverallKpis kpis = analyticsDao.fetchKpis(filter);
        assertThat(kpis.paidOrders()).isEqualTo(1);
        assertThat(kpis.grossGmv()).isEqualByComparingTo("100.00");
        assertThat(kpis.platformRevenue()).isEqualByComparingTo("10.00");

        assertThat(analyticsDao.fetchCanteenPerformances(filter))
                .singleElement()
                .satisfies(row -> assertThat(row.grossGmv()).isEqualByComparingTo("100.00"));
        assertThat(analyticsDao.fetchDailyTrends(filter).stream()
                .map(day -> day.grossGmv()).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("100.00");
    }

    @Test
    void theAdminDashboardRevenueDoesNotMoveForAReviewOrder() {
        // Global across every college, so measured as a change rather than a total. In the
        // backfill college, so the figures the other tests check stay put.
        String runId = UUID.randomUUID().toString().substring(0, 6);
        BigDecimal before = dashboardDao.platformSnapshot().revenueToday();

        backfillOrder("NC-D1-" + runId, "500.00", true);
        assertThat(dashboardDao.platformSnapshot().revenueToday()).isEqualByComparingTo(before);

        backfillOrder("NC-D2-" + runId, "7.00", false);
        assertThat(dashboardDao.platformSnapshot().revenueToday()).isEqualByComparingTo(before.add(new BigDecimal("7.00")));
    }

    @Test
    void theMigrationBackfillMatchesOnlyTheReviewCheckoutsPaymentIds() {
        // V38's backfill, run again against rows it must and must not match. LIKE treats _
        // as a wildcard, so the escaping is what stops "playXreview..." counting.
        Long real = backfillOrder("NC-B1-" + UUID.randomUUID().toString().substring(0, 6), "40.00", false);
        Long lookalike = backfillOrder("NC-B2-" + UUID.randomUUID().toString().substring(0, 6), "40.00", false);
        Long review = backfillOrder("NC-B3-" + UUID.randomUUID().toString().substring(0, 6), "40.00", false);
        payment(real, "order_" + UUID.randomUUID().toString().substring(0, 12));
        payment(lookalike, "playXreviewXorderX" + lookalike);
        payment(review, "play_review_order_" + review);

        String backfill = """
                UPDATE orders o
                JOIN payments p ON p.order_id = o.id
                SET o.no_charge = TRUE
                WHERE p.razorpay_order_id LIKE 'play\\_review\\_order\\_%'
                  AND o.tenant_id = ?""";
        jdbcTemplate.update(backfill, backfillTenantId);

        assertThat(orderDao.findByIdAndTenantId(real, backfillTenantId).orElseThrow().isNoCharge()).isFalse();
        assertThat(orderDao.findByIdAndTenantId(lookalike, backfillTenantId).orElseThrow().isNoCharge()).isFalse();
        assertThat(orderDao.findByIdAndTenantId(review, backfillTenantId).orElseThrow().isNoCharge()).isTrue();
    }

    private Long backfillOrder(String token, String amount, boolean noCharge) {
        return completedOrder(backfillTenantId, backfillOutletId, backfillStudentId, token, amount, noCharge);
    }

    private void payment(Long orderId, String gatewayOrderId) {
        jdbcTemplate.update("INSERT INTO payments (tenant_id, order_id, razorpay_order_id, amount, status) "
                + "VALUES (?, ?, ?, 40.00, 'CAPTURED')", backfillTenantId, orderId, gatewayOrderId);
    }
}
