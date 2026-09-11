package com.bitesite.stress;

import com.bitesite.dao.OrderDao;
import com.bitesite.dao.OutletDao;
import com.bitesite.dao.PaymentDao;
import com.bitesite.dao.UserDao;
import com.bitesite.dto.GatewayOrder;
import com.bitesite.dto.GatewayRefund;
import com.bitesite.model.Order;
import com.bitesite.model.OrderStatus;
import com.bitesite.model.Outlet;
import com.bitesite.model.Payment;
import com.bitesite.model.PaymentStatus;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import com.bitesite.service.OrderService;
import com.bitesite.service.PaymentGateway;
import com.bitesite.tenant.Tenant;
import com.bitesite.tenant.TenantDao;
import com.bitesite.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One order, several cancels arriving at once. The gateway must be asked to refund exactly
 * once.
 *
 * <p>This is the only place in the product where losing a race costs real money rather
 * than milliseconds. {@code cancelOrder} read the payment, saw CAPTURED, and called the
 * gateway — a check-then-act with a network call as the act, and nothing locked in between.
 * Two cancels arriving together, a student double-tapping on a slow connection or staff
 * cancelling from the outlet console at the same moment, could both pass the check.
 *
 * <p>Unlike the token and pickup-code races, no database constraint can catch this one:
 * the money leaves at Razorpay before anything local changes, so by the time a constraint
 * could fire it has already gone.
 *
 * <p>The stubbed refund sleeps briefly on purpose. Without it the window between the check
 * and the gateway call is a few microseconds and whether the bug reproduces is luck — and a
 * concurrency test that only fails sometimes is worse than none, because passing it proves
 * nothing. The sleep makes both threads certain to be inside the window together, which is
 * exactly the situation a slow Razorpay call creates in production anyway.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrentRefundTest {

    private static final int CONCURRENT_CANCELS = 8;

    static final AtomicInteger refundCalls = new AtomicInteger();
    static volatile boolean gatewayTimesOut = false;
    /** What Razorpay would say it holds if asked: gateway payment id -> refund. Written by
     * the timing-out path, because a refund that times out is one that DID happen. */
    static final java.util.Map<String, GatewayRefund> heldByGateway = new java.util.concurrent.ConcurrentHashMap<>();

    @TestConfiguration
    static class CountingGateway {
        @Bean
        @Primary
        PaymentGateway countingPaymentGateway() {
            return new PaymentGateway() {
                @Override
                public GatewayOrder createOrder(BigDecimal amount, String receipt) {
                    return new GatewayOrder("stub_" + UUID.randomUUID(), "k", 100L, "INR");
                }

                @Override
                public boolean verifyPaymentSignature(String o, String p, String s) {
                    return true;
                }

                @Override
                public boolean verifyWebhookSignature(String p, String h) {
                    return true;
                }

                @Override
                public void refund(String gatewayPaymentId, BigDecimal amount) {
                    refundCalls.incrementAndGet();
                    if (gatewayTimesOut) {
                        // Razorpay processed it; we never heard back. The dangerous case.
                        // It is recorded as held, because that is the whole point: the
                        // money moved even though the caller was told it did not.
                        heldByGateway.put(gatewayPaymentId,
                                new GatewayRefund("rfnd_" + UUID.randomUUID(), amount, "processed"));
                        throw new com.bitesite.exception.PaymentGatewayException(
                                "Could not process the refund — please try again.",
                                new RuntimeException("read timed out"));
                    }
                    try {
                        // Widens the check-then-act window to something a test can rely on.
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                @Override
                public List<GatewayRefund> refundsFor(String gatewayPaymentId) {
                    GatewayRefund held = heldByGateway.get(gatewayPaymentId);
                    return held == null ? List.of() : List.of(held);
                }
            };
        }
    }

    @Autowired private TenantDao tenantDao;
    @Autowired private OutletDao outletDao;
    @Autowired private UserDao userDao;
    @Autowired private OrderDao orderDao;
    @Autowired private PaymentDao paymentDao;
    @Autowired private OrderService orderService;

    private Long tenantId;
    private Long orderId;
    private Long secondOutletId;
    private Long studentId;

    @BeforeAll
    void seedOnePaidOrder() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        Tenant tenant = tenantDao.save(Tenant.builder().name("Refund College " + runId)
                .status(TenantStatus.ACTIVE).build());
        tenantId = tenant.getId();
        Outlet outlet = outletDao.save(Outlet.builder().tenantId(tenantId)
                .name("Refund Canteen").active(true).build());
        secondOutletId = outletDao.save(Outlet.builder().tenantId(tenantId)
                .name("Refund Canteen 2").active(true).build()).getId();
        User student = userDao.save(User.builder().tenantId(tenantId).name("Refund Student")
                .email("refund-" + runId + "@test.local").passwordHash("x")
                .role(Role.USER).activeRole(Role.USER).active(true).build());
        studentId = student.getId();

        Order order = orderDao.createOrder(Order.builder()
                .tenantId(tenantId).outletId(outlet.getId()).userId(student.getId())
                .tokenNo("RF-" + runId).totalAmount(new BigDecimal("60.00"))
                .status(OrderStatus.PAID).items(List.of()).build());
        orderId = order.getId();
        orderDao.updateStatus(orderId, tenantId, OrderStatus.PAID);

        paymentDao.save(Payment.builder().tenantId(tenantId).orderId(orderId)
                .razorpayOrderId("rp_order_" + runId).razorpayPaymentId("rp_pay_" + runId)
                .amount(new BigDecimal("60.00")).status(PaymentStatus.CAPTURED).build());
    }

    /** The counter is static because the stub gateway is a bean; reset it per test or one
     *  test's refunds are counted against the next one's assertions. */
    @BeforeEach
    void resetCounter() {
        refundCalls.set(0);
    }

    @Test
    void eightSimultaneousCancelsRefundTheStudentExactlyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CANCELS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_CANCELS);
        ConcurrentLinkedQueue<String> outcomes = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < CONCURRENT_CANCELS; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    orderService.cancelOrder(orderId, tenantId, null, "Cancelled by test");
                    outcomes.add("ok");
                } catch (Exception e) {
                    // Losers are expected to be refused; that is the correct outcome.
                    outcomes.add(e.getClass().getSimpleName());
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        boolean finished = done.await(2, TimeUnit.MINUTES);
        pool.shutdownNow();

        System.out.printf("%n[refund] %d concurrent cancels -> gateway.refund called %d time(s)%n",
                CONCURRENT_CANCELS, refundCalls.get());

        assertThat(finished).as("cancels did not finish in time").isTrue();
        assertThat(refundCalls.get())
                .as("the student's money must leave Razorpay exactly once, however many "
                        + "cancels arrive together")
                .isEqualTo(1);

        Payment after = paymentDao.findByOrderId(orderId, tenantId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(orderDao.findByIdAndTenantId(orderId, tenantId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    /**
     * The failure that actually costs money: the gateway succeeded and told us it did not.
     *
     * <p>A timeout must never return the payment to CAPTURED, because the money may well be
     * gone. If it did, the next cancel would claim cleanly and refund it a second time — the
     * exact bug this whole change exists to prevent, reached by a different door.
     */
    @Test
    void aRefundThatTimesOutIsLeftFlaggedAndCannotBeRefundedAgain() throws Exception {
        // A second order, so the first test's state is untouched.
        Order order = orderDao.createOrder(Order.builder()
                .tenantId(tenantId).outletId(secondOutletId).userId(studentId)
                .tokenNo("TO-" + UUID.randomUUID().toString().substring(0, 6))
                .totalAmount(new BigDecimal("60.00"))
                .status(OrderStatus.PAID).items(List.of()).build());
        orderDao.updateStatus(order.getId(), tenantId, OrderStatus.PAID);
        paymentDao.save(Payment.builder().tenantId(tenantId).orderId(order.getId())
                .razorpayOrderId("rp_o_" + UUID.randomUUID()).razorpayPaymentId("rp_p_" + UUID.randomUUID())
                .amount(new BigDecimal("60.00")).status(PaymentStatus.CAPTURED).build());

        gatewayTimesOut = true;
        int before = refundCalls.get();
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> orderService.cancelOrder(order.getId(), tenantId, null, "timeout case"))
                    .isInstanceOf(RuntimeException.class);

            Payment stuck = paymentDao.findByOrderId(order.getId(), tenantId).orElseThrow();
            assertThat(stuck.getStatus())
                    .as("a timed-out refund must NOT fall back to CAPTURED: the money may be gone, "
                            + "and CAPTURED invites a second refund")
                    .isEqualTo(PaymentStatus.REFUND_PENDING);
            assertThat(stuck.isNeedsReconciliation())
                    .as("an unknown outcome has to be visible to a human, not just logged")
                    .isTrue();

            // The order stays live, because we do not know the money moved.
            assertThat(orderDao.findByIdAndTenantId(order.getId(), tenantId).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.PAID);

            // And a second attempt must not reach the gateway at all.
            int afterFirst = refundCalls.get();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> orderService.cancelOrder(order.getId(), tenantId, null, "second try"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(refundCalls.get())
                    .as("the gateway must not be asked a second time for a payment whose first "
                            + "outcome is unresolved")
                    .isEqualTo(afterFirst);
        } finally {
            gatewayTimesOut = false;
        }
        assertThat(refundCalls.get() - before).isEqualTo(1);
    }
}
