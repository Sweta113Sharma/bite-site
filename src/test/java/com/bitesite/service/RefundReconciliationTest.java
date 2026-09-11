package com.bitesite.service;

import com.bitesite.dao.OrderDao;
import com.bitesite.dao.OutletDao;
import com.bitesite.dao.PaymentDao;
import com.bitesite.dao.UserDao;
import com.bitesite.dto.GatewayOrder;
import com.bitesite.dto.GatewayRefund;
import com.bitesite.exception.PaymentGatewayException;
import com.bitesite.model.Order;
import com.bitesite.model.OrderStatus;
import com.bitesite.model.Outlet;
import com.bitesite.model.Payment;
import com.bitesite.model.PaymentStatus;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import com.bitesite.tenant.Tenant;
import com.bitesite.tenant.TenantDao;
import com.bitesite.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What happens to a refund after the call that started it failed to say whether it worked.
 *
 * <p>The unit under test is really a claim about money: a refund whose HTTP call timed out
 * must end up either confirmed or visibly stuck, never quietly forgotten and never sent
 * twice. Both routes that can confirm it are exercised against a real database, because
 * both turn on conditional UPDATEs and a MySQL clock, and neither is provable with mocks.
 *
 * <p>The stub gateway is scriptable rather than fixed: each test says what Razorpay would
 * report if asked. That is the only interesting variable here, since the whole design
 * exists to cope with our copy of the truth and Razorpay's disagreeing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefundReconciliationTest {

    /** Gateway payment id -> what Razorpay would say it holds. */
    static final Map<String, List<GatewayRefund>> heldByGateway = new ConcurrentHashMap<>();
    /* Counted per payment, not in total. The sweep is deliberately global — it looks at
       every pending refund in the database — so it also picks up rows left behind by other
       tests and earlier runs, and a single total would be counting those too. */
    static final Map<String, AtomicInteger> refundCallsByPayment = new ConcurrentHashMap<>();
    static volatile boolean gatewayTimesOut = false;
    /** When true, a timing-out refund is one Razorpay never received. */
    static volatile boolean timeoutLosesTheRequest = false;

    @TestConfiguration
    static class ScriptableGateway {
        @Bean
        @Primary
        PaymentGateway scriptablePaymentGateway() {
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
                    refundCallsByPayment.computeIfAbsent(String.valueOf(gatewayPaymentId),
                            k -> new AtomicInteger()).incrementAndGet();
                    if (gatewayTimesOut) {
                        if (!timeoutLosesTheRequest) {
                            // The dangerous case: Razorpay did the work, we never heard.
                            heldByGateway.put(gatewayPaymentId, List.of(
                                    new GatewayRefund("rfnd_" + UUID.randomUUID(), amount, "processed")));
                        }
                        throw new PaymentGatewayException("Could not process the refund — please try again.",
                                new RuntimeException("read timed out"));
                    }
                    heldByGateway.put(gatewayPaymentId, List.of(
                            new GatewayRefund("rfnd_" + UUID.randomUUID(), amount, "processed")));
                }

                @Override
                public List<GatewayRefund> refundsFor(String gatewayPaymentId) {
                    return heldByGateway.getOrDefault(gatewayPaymentId, List.of());
                }
            };
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantDao tenantDao;
    @Autowired private OutletDao outletDao;
    @Autowired private UserDao userDao;
    @Autowired private OrderDao orderDao;
    @Autowired private PaymentDao paymentDao;
    @Autowired private OrderService orderService;
    @Autowired private RefundReconciliationService reconciliation;

    private Long tenantId;
    private Long outletId;
    private Long studentId;

    @BeforeAll
    void seed() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        tenantId = tenantDao.save(Tenant.builder().name("Reconcile College " + runId)
                .status(TenantStatus.ACTIVE).build()).getId();
        outletId = outletDao.save(Outlet.builder().tenantId(tenantId)
                .name("Reconcile Canteen").active(true).build()).getId();
        studentId = userDao.save(User.builder().tenantId(tenantId).name("Reconcile Student")
                .email("reconcile-" + runId + "@test.local").passwordHash("x")
                .role(Role.USER).activeRole(Role.USER).active(true).build()).getId();
    }

    @BeforeEach
    void reset() {
        refundCallsByPayment.clear();
        gatewayTimesOut = false;
        timeoutLosesTheRequest = false;
        heldByGateway.clear();
    }

    /** How many times the gateway was asked to refund this specific payment. */
    private int refundCallsFor(Long paymentId) {
        AtomicInteger calls = refundCallsByPayment.get(reload(paymentId).getRazorpayPaymentId());
        return calls == null ? 0 : calls.get();
    }

    /** A paid order with a captured payment, ready to be cancelled. */
    private Payment paidOrder() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        Order order = orderDao.createOrder(Order.builder()
                .tenantId(tenantId).outletId(outletId).userId(studentId)
                .tokenNo("RC-" + runId).totalAmount(new BigDecimal("60.00"))
                .status(OrderStatus.PAID).items(List.of()).build());
        orderDao.updateStatus(order.getId(), tenantId, OrderStatus.PAID);
        Payment payment = paymentDao.save(Payment.builder().tenantId(tenantId).orderId(order.getId())
                .razorpayOrderId("rp_o_" + runId).amount(new BigDecimal("60.00"))
                .status(PaymentStatus.CREATED).build());
        paymentDao.markVerified(payment.getId(), "rp_p_" + runId, null, PaymentStatus.CAPTURED);
        return paymentDao.findByOrderId(order.getId(), tenantId).orElseThrow();
    }

    private Payment reload(Long paymentId) {
        return paymentDao.findRecentAcrossTenants(null, 500, 0).stream()
                .filter(p -> p.getId().equals(paymentId)).findFirst().orElseThrow();
    }

    private OrderStatus orderStatus(Long orderId) {
        return orderDao.findByIdAndTenantId(orderId, tenantId).orElseThrow().getStatus();
    }

    /**
     * The case the whole design is for. The refund went through at Razorpay, our call timed
     * out, and nobody knew. The sweep asks, and the cancellation finishes itself.
     */
    @Test
    void aRefundThatTimedOutIsSettledBySweepingAndTheOrderIsThenCancelled() {
        Payment payment = paidOrder();
        gatewayTimesOut = true;
        assertThatThrownBy(() -> orderService.cancelOrder(payment.getOrderId(), tenantId, studentId,
                "Ingredients ran out")).isInstanceOf(RuntimeException.class);
        gatewayTimesOut = false;

        assertThat(reload(payment.getId()).getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(orderStatus(payment.getOrderId())).isEqualTo(OrderStatus.PAID);

        // 0 minutes: the row was claimed a moment ago, and a test cannot wait ten minutes
        // for the real threshold. The age check itself is proved below.
        reconciliation.reconcilePending(0);

        Payment settled = reload(payment.getId());
        assertThat(settled.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(settled.isNeedsReconciliation())
                .as("a settled refund is no longer a human's problem").isFalse();
        assertThat(orderStatus(payment.getOrderId())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(orderDao.findByIdAndTenantId(payment.getOrderId(), tenantId).orElseThrow()
                .getCancellationReason())
                .as("the reason the cancel was asked for must survive the timeout")
                .isEqualTo("Ingredients ran out");
        assertThat(refundCallsFor(payment.getId()))
                .as("Razorpay already had the refund; it must not be asked again").isEqualTo(1);
    }

    /** The other half: the request never arrived, so the sweep sends it again. */
    @Test
    void aRefundRazorpayNeverReceivedIsSentAgainBySweeping() {
        Payment payment = paidOrder();
        gatewayTimesOut = true;
        timeoutLosesTheRequest = true;
        assertThatThrownBy(() -> orderService.cancelOrder(payment.getOrderId(), tenantId, studentId,
                "Kitchen closed early")).isInstanceOf(RuntimeException.class);
        gatewayTimesOut = false;
        timeoutLosesTheRequest = false;

        reconciliation.reconcilePending(0);

        assertThat(refundCallsFor(payment.getId())).as("one failed attempt, then one retry").isEqualTo(2);
        assertThat(reload(payment.getId()).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(orderStatus(payment.getOrderId())).isEqualTo(OrderStatus.CANCELLED);
    }

    /**
     * The guard that makes the sweep safe to run while cancels are happening: a refund
     * claimed moments ago may still be in flight, so it is left alone.
     */
    @Test
    void aRefundStillInFlightIsNotTouchedBySweeping() {
        Payment payment = paidOrder();
        gatewayTimesOut = true;
        timeoutLosesTheRequest = true;
        assertThatThrownBy(() -> orderService.cancelOrder(payment.getOrderId(), tenantId, studentId,
                "too soon")).isInstanceOf(RuntimeException.class);
        gatewayTimesOut = false;
        timeoutLosesTheRequest = false;

        reconciliation.reconcilePending(10);
        assertThat(refundCallsFor(payment.getId()))
                .as("the gateway must not be asked again yet").isEqualTo(1);
        assertThat(reload(payment.getId()).getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
    }

    /** Razorpay's own webhook, through the real endpoint, settles the same timeout. */
    @Test
    void theRefundProcessedWebhookSettlesATimedOutRefund() throws Exception {
        Payment payment = paidOrder();
        gatewayTimesOut = true;
        assertThatThrownBy(() -> orderService.cancelOrder(payment.getOrderId(), tenantId, studentId,
                "webhook settles this")).isInstanceOf(RuntimeException.class);
        gatewayTimesOut = false;

        String gatewayPaymentId = reload(payment.getId()).getRazorpayPaymentId();
        mockMvc.perform(post("/api/payments/webhook")
                        .header("X-Razorpay-Signature", "stubbed-as-valid")
                        .contentType("application/json")
                        .content(refundEvent("refund.processed", gatewayPaymentId, 6000)))
                .andExpect(status().isOk());

        Payment settled = reload(payment.getId());
        assertThat(settled.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(settled.isNeedsReconciliation()).isFalse();
        assertThat(orderStatus(payment.getOrderId())).isEqualTo(OrderStatus.CANCELLED);
        // And the sweep then has nothing to do, which is the property that stops the two
        // routes both cancelling the order or both refunding.
        reconciliation.reconcilePending(0);
        assertThat(refundCallsFor(payment.getId())).isEqualTo(1);
    }

    /** refund.failed means the money never left. The order must stay live and payable. */
    @Test
    void theRefundFailedWebhookReturnsTheMoneyToCapturedAndFlagsIt() throws Exception {
        Payment payment = paidOrder();
        gatewayTimesOut = true;
        timeoutLosesTheRequest = true;
        assertThatThrownBy(() -> orderService.cancelOrder(payment.getOrderId(), tenantId, studentId,
                "this one fails")).isInstanceOf(RuntimeException.class);
        gatewayTimesOut = false;
        timeoutLosesTheRequest = false;

        String gatewayPaymentId = reload(payment.getId()).getRazorpayPaymentId();
        mockMvc.perform(post("/api/payments/webhook")
                        .header("X-Razorpay-Signature", "stubbed-as-valid")
                        .contentType("application/json")
                        .content(refundEvent("refund.failed", gatewayPaymentId, 6000)))
                .andExpect(status().isOk());

        Payment failed = reload(payment.getId());
        assertThat(failed.getStatus())
                .as("a failed refund leaves the money with us, so the payment is CAPTURED again")
                .isEqualTo(PaymentStatus.CAPTURED);
        assertThat(failed.isNeedsReconciliation()).isTrue();
        assertThat(orderStatus(payment.getOrderId()))
                .as("nothing was refunded, so nothing is cancelled").isEqualTo(OrderStatus.PAID);
    }

    /**
     * A partial refund can only have been made by hand at Razorpay's dashboard, since
     * BiteSite never issues one. Guessing what it meant is worse than saying so.
     */
    @Test
    void aPartialRefundAtTheGatewayIsFlaggedRatherThanSettled() {
        Payment payment = paidOrder();
        gatewayTimesOut = true;
        timeoutLosesTheRequest = true;
        assertThatThrownBy(() -> orderService.cancelOrder(payment.getOrderId(), tenantId, studentId,
                "partial")).isInstanceOf(RuntimeException.class);
        gatewayTimesOut = false;
        timeoutLosesTheRequest = false;

        String gatewayPaymentId = reload(payment.getId()).getRazorpayPaymentId();
        heldByGateway.put(gatewayPaymentId, List.of(
                new GatewayRefund("rfnd_partial", new BigDecimal("25.00"), "processed")));

        reconciliation.reconcilePending(0);

        Payment flagged = reload(payment.getId());
        assertThat(flagged.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(flagged.isNeedsReconciliation()).isTrue();
        assertThat(flagged.getReconciliationReason()).contains("only issues full refunds");
        assertThat(refundCallsFor(payment.getId()))
                .as("never top up a partial refund automatically").isEqualTo(1);
    }

    /** Razorpay's payload shape, as documented for refund.* events. */
    private static String refundEvent(String event, String gatewayPaymentId, long amountPaise) {
        return """
                {"event":"%s","payload":{"refund":{"entity":{
                  "id":"rfnd_%s","amount":%d,"currency":"INR","payment_id":"%s","status":"%s"}}}}
                """.formatted(event, UUID.randomUUID().toString().substring(0, 8), amountPaise,
                gatewayPaymentId, event.endsWith("failed") ? "failed" : "processed");
    }
}
