package com.bitesite.service;

import com.bitesite.dao.OrderDao;
import com.bitesite.dao.PaymentDao;
import com.bitesite.dto.CheckoutResult;
import com.bitesite.dto.GatewayOrder;
import com.bitesite.exception.InvalidOrderStateException;
import com.bitesite.model.MenuItem;
import com.bitesite.model.Order;
import com.bitesite.model.OrderStatus;
import com.bitesite.model.Outlet;
import com.bitesite.model.Payment;
import com.bitesite.model.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderDao orderDao;
    @Mock private PaymentDao paymentDao;
    @Mock private MenuService menuService;
    @Mock private OutletService outletService;
    @Mock private PaymentGateway paymentGateway;
    @Mock private AuditService auditService;
    @Mock private OrderNotifier orderNotifier;

    private OrderService orderService;

    private static final Long TENANT_ID = 1L;
    private static final Long OUTLET_ID = 10L;
    private static final Long USER_ID = 100L;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderDao, paymentDao, menuService, outletService, paymentGateway,
                auditService, orderNotifier);
    }

    private MenuItem availableItem(long id, String name, BigDecimal price) {
        return MenuItem.builder().id(id).tenantId(TENANT_ID).outletId(OUTLET_ID)
                .name(name).category("Snacks").price(price).available(true).build();
    }

    /** Every checkout starts by asking the outlet whether it is open; most tests want yes. */
    private void outletIsOpen() {
        when(outletService.get(OUTLET_ID, TENANT_ID)).thenReturn(Outlet.builder()
                .id(OUTLET_ID).tenantId(TENANT_ID).name("Main Canteen").active(true).acceptingOrders(true).build());
    }

    @Test
    void checkoutRejectsAnEmptyCart() {
        assertThatThrownBy(() -> orderService.checkout(TENANT_ID, OUTLET_ID, USER_ID, Map.of()))
                .isInstanceOf(InvalidOrderStateException.class);
        verifyNoInteractions(orderDao, paymentGateway, paymentDao, outletService);
    }

    @Test
    void checkoutRejectsAnUnavailableItem() {
        outletIsOpen();
        MenuItem unavailable = MenuItem.builder().id(5L).tenantId(TENANT_ID).outletId(OUTLET_ID)
                .name("Samosa").category("Snacks").price(new BigDecimal("30.00")).available(false).build();
        when(menuService.get(5L, TENANT_ID)).thenReturn(unavailable);

        Map<Long, Integer> cart = new LinkedHashMap<>();
        cart.put(5L, 2);

        assertThatThrownBy(() -> orderService.checkout(TENANT_ID, OUTLET_ID, USER_ID, cart))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("Samosa");
        verify(orderDao, never()).createOrder(any());
    }

    @Test
    void checkoutRejectsAnItemFromADifferentOutlet() {
        outletIsOpen();
        MenuItem wrongOutlet = MenuItem.builder().id(5L).tenantId(TENANT_ID).outletId(999L)
                .name("Samosa").category("Snacks").price(new BigDecimal("30.00")).available(true).build();
        when(menuService.get(5L, TENANT_ID)).thenReturn(wrongOutlet);

        Map<Long, Integer> cart = new LinkedHashMap<>();
        cart.put(5L, 1);

        assertThatThrownBy(() -> orderService.checkout(TENANT_ID, OUTLET_ID, USER_ID, cart))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    void checkoutRepricesFromTheDatabaseNotTheClient() {
        outletIsOpen();
        when(menuService.get(5L, TENANT_ID)).thenReturn(availableItem(5L, "Samosa", new BigDecimal("30.00")));
        when(orderDao.existsTokenForTenantToday(eq(TENANT_ID), any())).thenReturn(false);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        when(orderDao.createOrder(orderCaptor.capture())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(42L);
            return o;
        });
        when(paymentGateway.createOrder(any(), any()))
                .thenReturn(new GatewayOrder("razorpay_order_1", "key_test", 6000, "INR"));

        Map<Long, Integer> cart = new LinkedHashMap<>();
        cart.put(5L, 2);

        CheckoutResult result = orderService.checkout(TENANT_ID, OUTLET_ID, USER_ID, cart);

        Order created = orderCaptor.getValue();
        assertThat(created.getTotalAmount()).isEqualByComparingTo("60.00");
        assertThat(created.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(created.getItems()).hasSize(1);
        assertThat(created.getItems().get(0).getUnitPrice()).isEqualByComparingTo("30.00");
        assertThat(result.gatewayOrder().gatewayOrderId()).isEqualTo("razorpay_order_1");
        verify(paymentDao).save(any(Payment.class));
    }

    @Test
    void checkoutMarksTheOrderPaymentFailedWhenTheGatewayCallThrows() {
        outletIsOpen();
        when(menuService.get(5L, TENANT_ID)).thenReturn(availableItem(5L, "Samosa", new BigDecimal("30.00")));
        when(orderDao.existsTokenForTenantToday(eq(TENANT_ID), any())).thenReturn(false);
        when(orderDao.createOrder(any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(42L);
            return o;
        });
        when(paymentGateway.createOrder(any(), any())).thenThrow(new RuntimeException("gateway down"));

        Map<Long, Integer> cart = new LinkedHashMap<>();
        cart.put(5L, 1);

        assertThatThrownBy(() -> orderService.checkout(TENANT_ID, OUTLET_ID, USER_ID, cart))
                .isInstanceOf(RuntimeException.class);

        verify(orderDao).updateStatus(42L, TENANT_ID, OrderStatus.PAYMENT_FAILED);
        verify(paymentDao, never()).save(any());
    }

    @Test
    void confirmPaymentIsIdempotentOnceAlreadyCaptured() {
        Payment captured = Payment.builder().id(1L).tenantId(TENANT_ID).orderId(42L)
                .razorpayOrderId("rp_order_1").amount(new BigDecimal("60.00")).status(PaymentStatus.CAPTURED).build();
        when(paymentDao.findByRazorpayOrderId("rp_order_1")).thenReturn(Optional.of(captured));

        boolean result = orderService.confirmPayment("rp_order_1", "rp_pay_1", "sig");

        assertThat(result).isTrue();
        verify(paymentGateway, never()).verifyPaymentSignature(any(), any(), any());
        verify(paymentDao, never()).markVerified(any(), any(), any(), any());
    }

    @Test
    void confirmPaymentRejectsAnInvalidSignatureAndDoesNotTouchTheOrder() {
        Payment pending = Payment.builder().id(1L).tenantId(TENANT_ID).orderId(42L)
                .razorpayOrderId("rp_order_1").amount(new BigDecimal("60.00")).status(PaymentStatus.CREATED).build();
        when(paymentDao.findByRazorpayOrderId("rp_order_1")).thenReturn(Optional.of(pending));
        when(paymentGateway.verifyPaymentSignature("rp_order_1", "rp_pay_1", "bad_sig")).thenReturn(false);

        boolean result = orderService.confirmPayment("rp_order_1", "rp_pay_1", "bad_sig");

        assertThat(result).isFalse();
        verify(paymentDao).updateStatus(1L, PaymentStatus.FAILED);
        verify(orderDao, never()).updateStatus(anyLong(), anyLong(), eq(OrderStatus.PAID));
    }

    @Test
    void confirmPaymentMarksPaidOnAValidClientSignature() {
        Payment pending = Payment.builder().id(1L).tenantId(TENANT_ID).orderId(42L)
                .razorpayOrderId("rp_order_1").amount(new BigDecimal("60.00")).status(PaymentStatus.CREATED).build();
        when(paymentDao.findByRazorpayOrderId("rp_order_1")).thenReturn(Optional.of(pending));
        when(paymentGateway.verifyPaymentSignature("rp_order_1", "rp_pay_1", "good_sig")).thenReturn(true);
        Order awaitingPayment = Order.builder().id(42L).tenantId(TENANT_ID).outletId(OUTLET_ID).userId(USER_ID)
                .tokenNo("BITE-1234").totalAmount(new BigDecimal("60.00")).status(OrderStatus.AWAITING_PAYMENT).build();
        when(orderDao.findByIdAndTenantId(42L, TENANT_ID)).thenReturn(Optional.of(awaitingPayment));

        boolean result = orderService.confirmPayment("rp_order_1", "rp_pay_1", "good_sig");

        assertThat(result).isTrue();
        verify(paymentDao).markVerified(1L, "rp_pay_1", "good_sig", PaymentStatus.CAPTURED);
        verify(orderDao).updateStatus(42L, TENANT_ID, OrderStatus.PAID);
    }

    @Test
    void confirmPaymentFromWebhookSkipsSignatureCheckSinceCallerAlreadyVerifiedTheWebhookItself() {
        Payment pending = Payment.builder().id(1L).tenantId(TENANT_ID).orderId(42L)
                .razorpayOrderId("rp_order_1").amount(new BigDecimal("60.00")).status(PaymentStatus.CREATED).build();
        when(paymentDao.findByRazorpayOrderId("rp_order_1")).thenReturn(Optional.of(pending));
        Order awaitingPayment = Order.builder().id(42L).tenantId(TENANT_ID).outletId(OUTLET_ID).userId(USER_ID)
                .tokenNo("BITE-1234").totalAmount(new BigDecimal("60.00")).status(OrderStatus.AWAITING_PAYMENT).build();
        when(orderDao.findByIdAndTenantId(42L, TENANT_ID)).thenReturn(Optional.of(awaitingPayment));

        boolean result = orderService.confirmPayment("rp_order_1", "rp_pay_1", null);

        assertThat(result).isTrue();
        verify(paymentGateway, never()).verifyPaymentSignature(any(), any(), any());
        verify(orderDao).updateStatus(42L, TENANT_ID, OrderStatus.PAID);
    }

    @Test
    void advanceStatusRejectsAnIllegalTransition() {
        Order paid = Order.builder().id(42L).tenantId(TENANT_ID).outletId(OUTLET_ID).userId(USER_ID)
                .tokenNo("BITE-1234").totalAmount(BigDecimal.TEN).status(OrderStatus.PAID).build();
        when(orderDao.findByIdAndTenantId(42L, TENANT_ID)).thenReturn(Optional.of(paid));

        assertThatThrownBy(() -> orderService.advanceStatus(42L, TENANT_ID, OrderStatus.COMPLETED, USER_ID))
                .isInstanceOf(InvalidOrderStateException.class);
        verify(orderDao, never()).updateStatus(anyLong(), anyLong(), any());
    }

    @Test
    void advanceStatusAppliesALegalTransitionAndAudits() {
        Order paid = Order.builder().id(42L).tenantId(TENANT_ID).outletId(OUTLET_ID).userId(USER_ID)
                .tokenNo("BITE-1234").totalAmount(BigDecimal.TEN).status(OrderStatus.PAID).build();
        when(orderDao.findByIdAndTenantId(42L, TENANT_ID)).thenReturn(Optional.of(paid));

        orderService.advanceStatus(42L, TENANT_ID, OrderStatus.PREPARING, USER_ID);

        verify(orderDao).updateStatus(42L, TENANT_ID, OrderStatus.PREPARING);
        verify(auditService).record(eq(USER_ID), eq(TENANT_ID), eq("Order"), eq(42L), eq("STATUS_PREPARING"), any(), any());
    }

    @Test
    void cancelOrderRejectsATerminalOrder() {
        Order completed = Order.builder().id(42L).tenantId(TENANT_ID).outletId(OUTLET_ID).userId(USER_ID)
                .tokenNo("BITE-1234").totalAmount(BigDecimal.TEN).status(OrderStatus.COMPLETED).build();
        when(orderDao.findByIdAndTenantId(42L, TENANT_ID)).thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> orderService.cancelOrder(42L, TENANT_ID, USER_ID, "changed my mind"))
                .isInstanceOf(InvalidOrderStateException.class);
        verifyNoInteractions(paymentGateway);
        verify(orderDao, never()).cancel(anyLong(), anyLong(), any());
    }

    @Test
    void cancelOrderRefundsThroughTheGatewayBeforeCancellingAPaidOrder() {
        Order paid = Order.builder().id(42L).tenantId(TENANT_ID).outletId(OUTLET_ID).userId(USER_ID)
                .tokenNo("BITE-1234").totalAmount(new BigDecimal("60.00")).status(OrderStatus.PAID).build();
        when(orderDao.findByIdAndTenantId(42L, TENANT_ID)).thenReturn(Optional.of(paid));
        Payment captured = Payment.builder().id(1L).tenantId(TENANT_ID).orderId(42L)
                .razorpayPaymentId("rp_pay_1").amount(new BigDecimal("60.00")).status(PaymentStatus.CAPTURED).build();
        when(paymentDao.findByOrderId(42L, TENANT_ID)).thenReturn(Optional.of(captured));

        orderService.cancelOrder(42L, TENANT_ID, USER_ID, "Ingredients ran out");

        verify(paymentGateway).refund("rp_pay_1", new BigDecimal("60.00"));
        verify(paymentDao).updateStatus(1L, PaymentStatus.REFUNDED);
        verify(orderDao).cancel(42L, TENANT_ID, "Ingredients ran out");
        verify(auditService).record(eq(USER_ID), eq(TENANT_ID), eq("Order"), eq(42L), eq("STATUS_CANCELLED"), any(), any());
    }

    @Test
    void cancelOrderDoesNotTouchTheOrderWhenTheRefundFails() {
        Order paid = Order.builder().id(42L).tenantId(TENANT_ID).outletId(OUTLET_ID).userId(USER_ID)
                .tokenNo("BITE-1234").totalAmount(new BigDecimal("60.00")).status(OrderStatus.PAID).build();
        when(orderDao.findByIdAndTenantId(42L, TENANT_ID)).thenReturn(Optional.of(paid));
        Payment captured = Payment.builder().id(1L).tenantId(TENANT_ID).orderId(42L)
                .razorpayPaymentId("rp_pay_1").amount(new BigDecimal("60.00")).status(PaymentStatus.CAPTURED).build();
        when(paymentDao.findByOrderId(42L, TENANT_ID)).thenReturn(Optional.of(captured));
        doThrow(new RuntimeException("gateway down")).when(paymentGateway).refund("rp_pay_1", new BigDecimal("60.00"));

        assertThatThrownBy(() -> orderService.cancelOrder(42L, TENANT_ID, USER_ID, "Kitchen closing early"))
                .isInstanceOf(RuntimeException.class);

        verify(paymentDao, never()).updateStatus(anyLong(), eq(PaymentStatus.REFUNDED));
        verify(orderDao, never()).cancel(anyLong(), anyLong(), any());
    }

    @Test
    void cancelOrderSkipsTheRefundCallWhenNoPaymentWasCaptured() {
        Order awaitingPayment = Order.builder().id(42L).tenantId(TENANT_ID).outletId(OUTLET_ID).userId(USER_ID)
                .tokenNo("BITE-1234").totalAmount(BigDecimal.TEN).status(OrderStatus.AWAITING_PAYMENT).build();
        when(orderDao.findByIdAndTenantId(42L, TENANT_ID)).thenReturn(Optional.of(awaitingPayment));

        orderService.cancelOrder(42L, TENANT_ID, USER_ID, null);

        verifyNoInteractions(paymentGateway, paymentDao);
        // A blank reason still leaves the student a sentence rather than an empty field.
        verify(orderDao).cancel(42L, TENANT_ID, "Cancelled by the canteen");
    }

    // ---------- Manual refunds from the support desk ----------

    private Order orderInStatus(OrderStatus status) {
        return Order.builder().id(42L).tenantId(TENANT_ID).outletId(OUTLET_ID).userId(USER_ID)
                .tokenNo("BITE-1234").totalAmount(new BigDecimal("60.00")).status(status).build();
    }

    private Payment paymentInStatus(PaymentStatus status) {
        return Payment.builder().id(1L).tenantId(TENANT_ID).orderId(42L)
                .razorpayPaymentId("rp_pay_1").amount(new BigDecimal("60.00")).status(status).build();
    }

    @Test
    void refundOrderRefundsAnOrderTheKitchenHadAlreadyStarted() {
        // The whole reason this method exists: PREPARING cannot reach CANCELLED through
        // the state machine, so cancelOrder can never refund this order.
        when(orderDao.findByIdAndTenantId(42L, TENANT_ID)).thenReturn(Optional.of(orderInStatus(OrderStatus.PREPARING)));
        when(paymentDao.findByOrderId(42L, TENANT_ID)).thenReturn(Optional.of(paymentInStatus(PaymentStatus.CAPTURED)));

        orderService.refundOrder(42L, TENANT_ID, USER_ID, "outlet closed early");

        verify(paymentGateway).refund("rp_pay_1", new BigDecimal("60.00"));
        verify(paymentDao).updateStatus(1L, PaymentStatus.REFUNDED);
        // The support agent's note becomes the student-visible cancellation reason.
        verify(orderDao).cancel(42L, TENANT_ID, "outlet closed early");
    }

    @Test
    void refundOrderLeavesACompletedOrderCompleted() {
        // The food was handed over. Refunding the money doesn't un-happen that, so the
        // order keeps its status and only the payment changes.
        when(orderDao.findByIdAndTenantId(42L, TENANT_ID)).thenReturn(Optional.of(orderInStatus(OrderStatus.COMPLETED)));
        when(paymentDao.findByOrderId(42L, TENANT_ID)).thenReturn(Optional.of(paymentInStatus(PaymentStatus.CAPTURED)));

        orderService.refundOrder(42L, TENANT_ID, USER_ID, "goodwill");

        verify(paymentDao).updateStatus(1L, PaymentStatus.REFUNDED);
        verify(orderDao, never()).cancel(anyLong(), anyLong(), any());
    }

    @Test
    void refundOrderDoesNotPayTwiceForTheSameOrder() {
        when(orderDao.findByIdAndTenantId(42L, TENANT_ID)).thenReturn(Optional.of(orderInStatus(OrderStatus.CANCELLED)));
        when(paymentDao.findByOrderId(42L, TENANT_ID)).thenReturn(Optional.of(paymentInStatus(PaymentStatus.REFUNDED)));

        assertThatThrownBy(() -> orderService.refundOrder(42L, TENANT_ID, USER_ID, "double click"))
                .isInstanceOf(InvalidOrderStateException.class);

        verifyNoInteractions(paymentGateway);
    }

    @Test
    void refundOrderRefusesAPaymentThatWasNeverCaptured() {
        when(orderDao.findByIdAndTenantId(42L, TENANT_ID)).thenReturn(Optional.of(orderInStatus(OrderStatus.AWAITING_PAYMENT)));
        when(paymentDao.findByOrderId(42L, TENANT_ID)).thenReturn(Optional.of(paymentInStatus(PaymentStatus.CREATED)));

        assertThatThrownBy(() -> orderService.refundOrder(42L, TENANT_ID, USER_ID, "mistake"))
                .isInstanceOf(InvalidOrderStateException.class);

        verifyNoInteractions(paymentGateway);
    }

    @Test
    void refundOrderChangesNothingWhenTheGatewayFails() {
        when(orderDao.findByIdAndTenantId(42L, TENANT_ID)).thenReturn(Optional.of(orderInStatus(OrderStatus.PREPARING)));
        when(paymentDao.findByOrderId(42L, TENANT_ID)).thenReturn(Optional.of(paymentInStatus(PaymentStatus.CAPTURED)));
        doThrow(new RuntimeException("gateway down")).when(paymentGateway).refund("rp_pay_1", new BigDecimal("60.00"));

        assertThatThrownBy(() -> orderService.refundOrder(42L, TENANT_ID, USER_ID, "outlet closed"))
                .isInstanceOf(RuntimeException.class);

        verify(paymentDao, never()).updateStatus(anyLong(), any());
        verify(orderDao, never()).cancel(anyLong(), anyLong(), any());
    }

    // ---------- Outlet gating and per-item daily caps at checkout ----------

    private void outletIs(boolean active, boolean acceptingOrders) {
        when(outletService.get(OUTLET_ID, TENANT_ID)).thenReturn(Outlet.builder()
                .id(OUTLET_ID).tenantId(TENANT_ID).name("Main Canteen")
                .active(active).acceptingOrders(acceptingOrders).build());
    }

    @Test
    void checkoutRefusesWhenTheOutletHasPausedNewOrders() {
        outletIs(true, false);

        assertThatThrownBy(() -> orderService.checkout(TENANT_ID, OUTLET_ID, USER_ID, Map.of(5L, 1)))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("paused");
        verify(orderDao, never()).createOrder(any());
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void checkoutRefusesWhenTheOutletHasBeenDeactivated() {
        outletIs(false, true);

        assertThatThrownBy(() -> orderService.checkout(TENANT_ID, OUTLET_ID, USER_ID, Map.of(5L, 1)))
                .isInstanceOf(InvalidOrderStateException.class);
        verify(orderDao, never()).createOrder(any());
    }

    @Test
    void checkoutRefusesAnItemAlreadyAtItsDailyLimit() {
        outletIs(true, true);
        MenuItem dosa = MenuItem.builder().id(5L).tenantId(TENANT_ID).outletId(OUTLET_ID).name("Masala Dosa")
                .category("Meals").price(new BigDecimal("60.00")).available(true).dailyLimit(30).build();
        when(menuService.get(5L, TENANT_ID)).thenReturn(dosa);
        when(menuService.soldToday(TENANT_ID, OUTLET_ID)).thenReturn(Map.of(5L, 30));

        assertThatThrownBy(() -> orderService.checkout(TENANT_ID, OUTLET_ID, USER_ID, Map.of(5L, 1)))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("sold out for today");
        verify(orderDao, never()).createOrder(any());
    }

    @Test
    void checkoutRefusesMoreThanTheDayHasLeftAndSaysHowMany() {
        outletIs(true, true);
        MenuItem dosa = MenuItem.builder().id(5L).tenantId(TENANT_ID).outletId(OUTLET_ID).name("Masala Dosa")
                .category("Meals").price(new BigDecimal("60.00")).available(true).dailyLimit(30).build();
        when(menuService.get(5L, TENANT_ID)).thenReturn(dosa);
        when(menuService.soldToday(TENANT_ID, OUTLET_ID)).thenReturn(Map.of(5L, 28));

        assertThatThrownBy(() -> orderService.checkout(TENANT_ID, OUTLET_ID, USER_ID, Map.of(5L, 3)))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("Only 2 left");
    }

    @Test
    void checkoutAllowsExactlyTheRemainingQuantity() {
        outletIs(true, true);
        MenuItem dosa = MenuItem.builder().id(5L).tenantId(TENANT_ID).outletId(OUTLET_ID).name("Masala Dosa")
                .category("Meals").price(new BigDecimal("60.00")).available(true).dailyLimit(30).build();
        when(menuService.get(5L, TENANT_ID)).thenReturn(dosa);
        when(menuService.soldToday(TENANT_ID, OUTLET_ID)).thenReturn(Map.of(5L, 28));
        when(orderDao.existsTokenForTenantToday(eq(TENANT_ID), any())).thenReturn(false);
        when(orderDao.createOrder(any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(42L);
            return o;
        });
        when(paymentGateway.createOrder(any(), any()))
                .thenReturn(new GatewayOrder("razorpay_order_1", "key_test", 12000, "INR"));

        CheckoutResult result = orderService.checkout(TENANT_ID, OUTLET_ID, USER_ID, Map.of(5L, 2));

        assertThat(result.order().getTotalAmount()).isEqualByComparingTo("120.00");
    }

    @Test
    void checkoutLeavesAnItemWithNoDailyLimitUncapped() {
        outletIs(true, true);
        when(menuService.get(5L, TENANT_ID)).thenReturn(availableItem(5L, "Chai", new BigDecimal("10.00")));
        when(menuService.soldToday(TENANT_ID, OUTLET_ID)).thenReturn(Map.of(5L, 9999));
        when(orderDao.existsTokenForTenantToday(eq(TENANT_ID), any())).thenReturn(false);
        when(orderDao.createOrder(any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(42L);
            return o;
        });
        when(paymentGateway.createOrder(any(), any()))
                .thenReturn(new GatewayOrder("razorpay_order_1", "key_test", 2000, "INR"));

        CheckoutResult result = orderService.checkout(TENANT_ID, OUTLET_ID, USER_ID, Map.of(5L, 2));

        assertThat(result.order().getTotalAmount()).isEqualByComparingTo("20.00");
    }

    // --- a capture that arrives after the order expired ---

    private Payment capturedPaymentFor(Order order) {
        return Payment.builder().id(500L).orderId(order.getId()).tenantId(order.getTenantId())
                .razorpayOrderId("order_late").amount(order.getTotalAmount())
                .status(PaymentStatus.CREATED).build();
    }

    /**
     * The money bug. The sweeper expires unpaid orders on a timer, and a bank OTP can
     * outlast it. Before this, the capture marked the payment CAPTURED, silently failed
     * the transition, and returned success — student charged, no order, nothing logged.
     */
    @Test
    void aPaymentCapturedAfterTheOrderExpiredRevivesTheOrder() {
        Order expired = Order.builder().id(90L).tenantId(TENANT_ID).userId(USER_ID).tokenNo("BITE-0090")
                .totalAmount(new BigDecimal("60.00")).status(OrderStatus.EXPIRED).build();
        Payment payment = capturedPaymentFor(expired);
        when(paymentDao.findByRazorpayOrderId("order_late")).thenReturn(Optional.of(payment));
        when(orderDao.findByIdAndTenantId(90L, TENANT_ID)).thenReturn(Optional.of(expired));
        when(paymentGateway.verifyPaymentSignature(any(), any(), any())).thenReturn(true);

        assertThat(orderService.confirmPayment("order_late", "pay_late", "sig")).isTrue();

        verify(orderDao).updateStatus(90L, TENANT_ID, OrderStatus.PAID);
        verify(paymentDao, never()).flagForReconciliation(any(), anyString());
        verify(orderNotifier).notifyOrderUpdate(eq(USER_ID), eq("Order confirmed"), anyString());
    }

    /** An order that genuinely cannot be honoured is money we hold wrongly, and that is a
     * refund — so it has to be visible rather than silently dropped. */
    @Test
    void aPaymentCapturedForACancelledOrderIsFlaggedForRefund() {
        Order cancelled = Order.builder().id(91L).tenantId(TENANT_ID).userId(USER_ID).tokenNo("BITE-0091")
                .totalAmount(new BigDecimal("60.00")).status(OrderStatus.CANCELLED).build();
        Payment payment = capturedPaymentFor(cancelled);
        when(paymentDao.findByRazorpayOrderId("order_late")).thenReturn(Optional.of(payment));
        when(orderDao.findByIdAndTenantId(91L, TENANT_ID)).thenReturn(Optional.of(cancelled));
        when(paymentGateway.verifyPaymentSignature(any(), any(), any())).thenReturn(true);

        assertThat(orderService.confirmPayment("order_late", "pay_late", "sig")).isTrue();

        verify(paymentDao).flagForReconciliation(eq(500L), anyString());
        verify(orderDao, never()).updateStatus(eq(91L), any(), eq(OrderStatus.PAID));
        // Nothing cheerful sent about an order that is not happening.
        verifyNoInteractions(orderNotifier);
    }
}
