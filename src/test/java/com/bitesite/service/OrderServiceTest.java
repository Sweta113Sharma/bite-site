package com.bitesite.service;

import com.bitesite.dao.OrderDao;
import com.bitesite.dao.PaymentDao;
import com.bitesite.dao.PlatformSettingsDao;
import com.bitesite.dto.CheckoutResult;
import com.bitesite.dto.GatewayOrder;
import com.bitesite.exception.BusinessException;
import com.bitesite.exception.InvalidOrderStateException;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.MenuItem;
import com.bitesite.model.Order;
import com.bitesite.model.PromoCode;
import com.bitesite.model.OrderSettings;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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
    @Mock private PromoCodeService promoCodeService;
    @Mock private RefundLedger refundLedger;

    private OrderService orderService;

    /**
     * One in-memory platform_settings table behind both settings-reading services. Real
     * services over a fake table rather than mocks, so a test that changes a setting
     * exercises the same parse-and-clamp path production does.
     */
    private final Map<String, String> platformSettings = new LinkedHashMap<>();

    private static final Long TENANT_ID = 1L;
    private static final Long OUTLET_ID = 10L;
    private static final Long USER_ID = 100L;

    @BeforeEach
    void setUp() {
        platformSettings.clear();
        PlatformSettingsDao settingsDao = new PlatformSettingsDao() {
            public Map<String, String> findAll() { return platformSettings; }
            public void upsert(String key, String value) { platformSettings.put(key, value); }
        };
        // A real BillingService over empty settings: every commercial control is inert by
        // default — no commission, no platform fee, no tip — so an order's total is still
        // exactly its food total and these tests assert what they always did. The same
        // emptiness leaves the cancellation window at its compiled-in default.
        BillingService billingService = new BillingService(settingsDao);
        PlatformSettingsService platformSettingsService =
                new PlatformSettingsService(settingsDao, auditService);
        orderService = new OrderService(orderDao, paymentDao, menuService, outletService, paymentGateway,
                auditService, orderNotifier, billingService, promoCodeService, platformSettingsService, refundLedger);
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
    void markingAnOrderReadyIssuesAPickupCodeAndPushesTheStudent() {
        Order preparing = Order.builder().id(42L).tenantId(TENANT_ID).outletId(OUTLET_ID).userId(USER_ID)
                .tokenNo("BITE-1234").totalAmount(BigDecimal.TEN).status(OrderStatus.PREPARING).build();
        when(orderDao.findByIdAndTenantId(42L, TENANT_ID)).thenReturn(Optional.of(preparing));
        when(orderDao.findActivePickupCodes(TENANT_ID, OUTLET_ID)).thenReturn(java.util.List.of("1111", "2222"));

        orderService.advanceStatus(42L, TENANT_ID, OrderStatus.READY_FOR_PICKUP, USER_ID);

        verify(orderDao).updateStatus(42L, TENANT_ID, OrderStatus.READY_FOR_PICKUP);
        verify(orderDao).setPickupCode(eq(42L), eq(TENANT_ID), anyString());
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(orderDao).setPickupCode(eq(42L), eq(TENANT_ID), codeCaptor.capture());
        assertThat(codeCaptor.getValue()).matches("\\d{4}").isNotIn("1111", "2222");
        verify(orderNotifier).notifyOrderUpdate(eq(USER_ID), eq("Order ready for pickup"),
                contains(codeCaptor.getValue()));
    }

    @Test
    void aNonReadyTransitionDoesNotPush() {
        Order paid = Order.builder().id(42L).tenantId(TENANT_ID).outletId(OUTLET_ID).userId(USER_ID)
                .tokenNo("BITE-1234").totalAmount(BigDecimal.TEN).status(OrderStatus.PAID).build();
        when(orderDao.findByIdAndTenantId(42L, TENANT_ID)).thenReturn(Optional.of(paid));

        orderService.advanceStatus(42L, TENANT_ID, OrderStatus.PREPARING, USER_ID);

        verify(orderNotifier, never()).notifyOrderUpdate(anyLong(), anyString(), anyString());
        verify(orderDao, never()).setPickupCode(anyLong(), anyLong(), anyString());
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
        when(refundLedger.claim(eq(1L), any(), any())).thenReturn(true);

        orderService.cancelOrder(42L, TENANT_ID, USER_ID, "Ingredients ran out");

        // The claim moves CAPTURED -> REFUND_PENDING and commits separately; the gateway call
        // then settles it to REFUNDED. Both halves matter, so both are asserted.
        verify(refundLedger).claim(eq(1L), eq("Ingredients ran out"), eq(USER_ID));
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
        when(refundLedger.claim(eq(1L), any(), any())).thenReturn(true);
        doThrow(new RuntimeException("gateway down")).when(paymentGateway).refund("rp_pay_1", new BigDecimal("60.00"));

        assertThatThrownBy(() -> orderService.cancelOrder(42L, TENANT_ID, USER_ID, "Kitchen closing early"))
                .isInstanceOf(RuntimeException.class);

        // A failed gateway call must NOT settle the payment to REFUNDED, must flag the
        // unknown outcome, and must leave the order alone.
        verify(paymentDao, never()).updateStatus(anyLong(), eq(PaymentStatus.REFUNDED));
        verify(refundLedger).recordUnresolved(eq(1L), contains("outcome unknown"));
        verify(orderDao, never()).cancel(anyLong(), anyLong(), any());
    }

    /* ---- the student's own cancellation window ---------------------------- */

    private Order paidOrderOwnedBy(long userId) {
        return Order.builder().id(42L).tenantId(TENANT_ID).outletId(OUTLET_ID).userId(userId)
                .tokenNo("BITE-1234").totalAmount(new BigDecimal("60.00")).status(OrderStatus.PAID).build();
    }

    @Test
    void aStudentCancellingInsideTheWindowIsRefunded() {
        when(orderDao.findByIdAndTenantId(42L, TENANT_ID)).thenReturn(Optional.of(paidOrderOwnedBy(USER_ID)));
        when(orderDao.isWithinSelfCancelWindow(42L, TENANT_ID, OrderSettings.DEFAULT_SELF_CANCEL_WINDOW_SECONDS))
                .thenReturn(true);
        Payment captured = Payment.builder().id(1L).tenantId(TENANT_ID).orderId(42L)
                .razorpayPaymentId("rp_pay_1").amount(new BigDecimal("60.00")).status(PaymentStatus.CAPTURED).build();
        when(paymentDao.findByOrderId(42L, TENANT_ID)).thenReturn(Optional.of(captured));
        when(refundLedger.claim(eq(1L), any(), any())).thenReturn(true);

        orderService.cancelOwnOrder(42L, USER_ID, TENANT_ID);

        verify(paymentGateway).refund("rp_pay_1", new BigDecimal("60.00"));
        verify(orderDao).cancel(eq(42L), eq(TENANT_ID), contains("student"));
    }

    /** The whole point of the window. Once shut, no refund call may be made at all. */
    @Test
    void aStudentCancellingAfterTheWindowIsRefusedAndNothingIsRefunded() {
        when(orderDao.findByIdAndTenantId(42L, TENANT_ID)).thenReturn(Optional.of(paidOrderOwnedBy(USER_ID)));
        when(orderDao.isWithinSelfCancelWindow(42L, TENANT_ID, OrderSettings.DEFAULT_SELF_CANCEL_WINDOW_SECONDS))
                .thenReturn(false);

        assertThatThrownBy(() -> orderService.cancelOwnOrder(42L, USER_ID, TENANT_ID))
                .isInstanceOf(InvalidOrderStateException.class);

        verifyNoInteractions(paymentGateway);
        verify(orderDao, never()).cancel(anyLong(), anyLong(), any());
    }

    /** Possession of an order id is not ownership; someone else's order is simply absent. */
    @Test
    void aStudentCannotCancelSomebodyElsesOrder() {
        when(orderDao.findByIdAndTenantId(42L, TENANT_ID)).thenReturn(Optional.of(paidOrderOwnedBy(999L)));

        assertThatThrownBy(() -> orderService.cancelOwnOrder(42L, USER_ID, TENANT_ID))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(paymentGateway);
        verify(orderDao, never()).cancel(anyLong(), anyLong(), any());
        // Ownership is settled before the clock is even consulted.
        verify(orderDao, never()).isWithinSelfCancelWindow(anyLong(), anyLong(), anyInt());
    }

    /**
     * The other half of the feature: the kitchen must not be shown an order the student can
     * still take back, so the queue is asked for the same window the cancel is judged against.
     */
    @Test
    void theKitchenQueueWithholdsOrdersForExactlyTheCancelWindow() {
        orderService.kitchenQueue(TENANT_ID, OUTLET_ID);

        verify(orderDao).findKitchenQueue(TENANT_ID, OUTLET_ID, OrderSettings.DEFAULT_SELF_CANCEL_WINDOW_SECONDS);
    }

    /**
     * The window is set from the admin console, and both halves have to move together. If
     * the queue and the cancel check ever read different numbers there would be a stretch
     * where an order is simultaneously cancellable and on the kitchen screen.
     */
    @Test
    void bothHalvesOfTheWindowFollowTheAdminSetting() {
        platformSettings.put(OrderSettings.SELF_CANCEL_WINDOW, "45");
        when(orderDao.findByIdAndTenantId(42L, TENANT_ID)).thenReturn(Optional.of(paidOrderOwnedBy(USER_ID)));
        when(orderDao.isWithinSelfCancelWindow(42L, TENANT_ID, 45)).thenReturn(false);

        orderService.kitchenQueue(TENANT_ID, OUTLET_ID);
        assertThatThrownBy(() -> orderService.cancelOwnOrder(42L, USER_ID, TENANT_ID))
                .isInstanceOf(InvalidOrderStateException.class);

        verify(orderDao).findKitchenQueue(TENANT_ID, OUTLET_ID, 45);
        verify(orderDao).isWithinSelfCancelWindow(42L, TENANT_ID, 45);
    }

    /**
     * Starting the window uses the same configured number as the two halves that read it.
     * A different number here would let the anchor be set after the kitchen already had it.
     */
    @Test
    void startingTheWindowUsesTheConfiguredNumberToo() {
        platformSettings.put(OrderSettings.SELF_CANCEL_WINDOW, "45");

        orderService.startCancelWindow(42L, TENANT_ID);

        verify(orderDao).startCancelWindow(42L, TENANT_ID, 45);
    }

    /** Zero is a real setting, not a missing one: cancellation off, kitchen sees everything. */
    @Test
    void aZeroWindowSwitchesCancellationOffRatherThanFallingBackToTheDefault() {
        platformSettings.put(OrderSettings.SELF_CANCEL_WINDOW, "0");

        orderService.kitchenQueue(TENANT_ID, OUTLET_ID);

        verify(orderDao).findKitchenQueue(TENANT_ID, OUTLET_ID, 0);
    }

    /**
     * A stray digit must not blind the kitchen for hours. The window is clamped on the way
     * in and again on the way out, so even a row written around the admin form is bounded.
     */
    @Test
    void anOversizedStoredWindowIsClampedBeforeTheKitchenEverSeesIt() {
        platformSettings.put(OrderSettings.SELF_CANCEL_WINDOW, "99999");

        orderService.kitchenQueue(TENANT_ID, OUTLET_ID);

        verify(orderDao).findKitchenQueue(TENANT_ID, OUTLET_ID,
                OrderSettings.MAX_SELF_CANCEL_WINDOW_SECONDS);
    }

    /** A corrupted row behaves as the platform did before this was configurable, not as "off". */
    @Test
    void anUnparseableStoredWindowFallsBackToTheDefault() {
        platformSettings.put(OrderSettings.SELF_CANCEL_WINDOW, "twenty");

        orderService.kitchenQueue(TENANT_ID, OUTLET_ID);

        verify(orderDao).findKitchenQueue(TENANT_ID, OUTLET_ID,
                OrderSettings.DEFAULT_SELF_CANCEL_WINDOW_SECONDS);
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
        when(refundLedger.claim(anyLong(), any(), any())).thenReturn(true);

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
        when(refundLedger.claim(anyLong(), any(), any())).thenReturn(true);

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
        when(refundLedger.claim(anyLong(), any(), any())).thenReturn(true);
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

    // ---- promo codes at checkout --------------------------------------------

    /**
     * The code is priced here, not in the session, and what it was worth is frozen onto
     * the order. Editing or deleting the code afterwards must not restate this bill.
     */
    @Test
    void checkoutSnapshotsWhatTheCodeWasWorthAndWhoFundedIt() {
        outletIsOpen();
        when(menuService.get(5L, TENANT_ID)).thenReturn(availableItem(5L, "Samosa", new BigDecimal("50.00")));
        when(orderDao.existsTokenForTenantToday(eq(TENANT_ID), any())).thenReturn(false);

        PromoCode code = PromoCode.builder().id(7L).code("SAVE20")
                .discountType(PromoCode.Type.FLAT).discountValue(new BigDecimal("20"))
                .fundedBy(PromoCode.Funder.PLATFORM).active(true).build();
        when(promoCodeService.validate("SAVE20", USER_ID, TENANT_ID, OUTLET_ID, new BigDecimal("100.00")))
                .thenReturn(new PromoCodeService.Applied(code, new BigDecimal("20.00")));

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        when(orderDao.createOrder(orderCaptor.capture())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(42L);
            return o;
        });
        when(paymentGateway.createOrder(any(), any()))
                .thenReturn(new GatewayOrder("razorpay_order_1", "key_test", 8000, "INR"));

        Map<Long, Integer> cart = new LinkedHashMap<>();
        cart.put(5L, 2);

        orderService.checkout(TENANT_ID, OUTLET_ID, USER_ID, cart, null, "SAVE20");

        Order created = orderCaptor.getValue();
        assertThat(created.getFoodAmount()).isEqualByComparingTo("100.00");
        assertThat(created.getDiscountAmount()).isEqualByComparingTo("20.00");
        assertThat(created.getDiscountFundedBy()).isEqualTo("PLATFORM");
        assertThat(created.getPromoCode()).isEqualTo("SAVE20");
        assertThat(created.getTotalAmount()).isEqualByComparingTo("80.00");
        // The gateway is asked for the discounted amount, not the menu price.
        verify(paymentGateway).createOrder(argThat(a -> a.compareTo(new BigDecimal("80.00")) == 0), any());
    }

    /** The redemption points at the order, so it can only be written once that row exists. */
    @Test
    void theRedemptionIsRecordedAgainstTheOrderThatUsedIt() {
        outletIsOpen();
        when(menuService.get(5L, TENANT_ID)).thenReturn(availableItem(5L, "Samosa", new BigDecimal("50.00")));
        when(orderDao.existsTokenForTenantToday(eq(TENANT_ID), any())).thenReturn(false);

        PromoCode code = PromoCode.builder().id(7L).code("SAVE20")
                .discountType(PromoCode.Type.FLAT).discountValue(new BigDecimal("20"))
                .fundedBy(PromoCode.Funder.CANTEEN).active(true).build();
        when(promoCodeService.validate(any(), any(), any(), any(), any()))
                .thenReturn(new PromoCodeService.Applied(code, new BigDecimal("20.00")));
        when(orderDao.createOrder(any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(42L);
            return o;
        });
        when(paymentGateway.createOrder(any(), any()))
                .thenReturn(new GatewayOrder("razorpay_order_1", "key_test", 3000, "INR"));

        Map<Long, Integer> cart = new LinkedHashMap<>();
        cart.put(5L, 1);

        orderService.checkout(TENANT_ID, OUTLET_ID, USER_ID, cart, null, "SAVE20");

        verify(promoCodeService).redeem(same(code), eq(42L), eq(USER_ID),
                argThat(d -> d.compareTo(new BigDecimal("20.00")) == 0));
    }

    /**
     * A code that has run out between the cart page and the pay button must stop the
     * checkout, not quietly charge full price. The student was shown ₹80; charging them
     * ₹100 because somebody else took the last redemption is the worst possible outcome.
     */
    @Test
    void aCodeThatStoppedApplyingRefusesTheCheckoutRatherThanChargingFullPrice() {
        outletIsOpen();
        when(menuService.get(5L, TENANT_ID)).thenReturn(availableItem(5L, "Samosa", new BigDecimal("50.00")));
        when(promoCodeService.validate(any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException("That code has been fully claimed."));

        Map<Long, Integer> cart = new LinkedHashMap<>();
        cart.put(5L, 1);

        assertThatThrownBy(() -> orderService.checkout(TENANT_ID, OUTLET_ID, USER_ID, cart, null, "SAVE20"))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("fully claimed");
        verify(orderDao, never()).createOrder(any());
        verify(paymentGateway, never()).createOrder(any(), any());
    }

    /** No code means the promo path is never touched at all. */
    @Test
    void aCheckoutWithoutACodeNeverConsultsThePromoService() {
        outletIsOpen();
        when(menuService.get(5L, TENANT_ID)).thenReturn(availableItem(5L, "Samosa", new BigDecimal("50.00")));
        when(orderDao.existsTokenForTenantToday(eq(TENANT_ID), any())).thenReturn(false);
        when(orderDao.createOrder(any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(42L);
            return o;
        });
        when(paymentGateway.createOrder(any(), any()))
                .thenReturn(new GatewayOrder("razorpay_order_1", "key_test", 5000, "INR"));

        Map<Long, Integer> cart = new LinkedHashMap<>();
        cart.put(5L, 1);

        orderService.checkout(TENANT_ID, OUTLET_ID, USER_ID, cart, null, "   ");

        verifyNoInteractions(promoCodeService);
    }

}
