package com.bitesite.service;

import com.bitesite.dao.OrderDao;
import com.bitesite.dao.PaymentDao;
import com.bitesite.dto.CheckoutResult;
import com.bitesite.dto.GatewayOrder;
import com.bitesite.exception.InvalidOrderStateException;
import com.bitesite.model.MenuItem;
import com.bitesite.model.Order;
import com.bitesite.model.OrderStatus;
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
    @Mock private PaymentGateway paymentGateway;
    @Mock private AuditService auditService;

    private OrderService orderService;

    private static final Long TENANT_ID = 1L;
    private static final Long OUTLET_ID = 10L;
    private static final Long USER_ID = 100L;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderDao, paymentDao, menuService, paymentGateway, auditService);
    }

    private MenuItem availableItem(long id, String name, BigDecimal price) {
        return MenuItem.builder().id(id).tenantId(TENANT_ID).outletId(OUTLET_ID)
                .name(name).category("Snacks").price(price).available(true).build();
    }

    @Test
    void checkoutRejectsAnEmptyCart() {
        assertThatThrownBy(() -> orderService.checkout(TENANT_ID, OUTLET_ID, USER_ID, Map.of()))
                .isInstanceOf(InvalidOrderStateException.class);
        verifyNoInteractions(orderDao, paymentGateway, paymentDao);
    }

    @Test
    void checkoutRejectsAnUnavailableItem() {
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
        when(menuService.get(5L, TENANT_ID)).thenReturn(availableItem(5L, "Samosa", new BigDecimal("30.00")));
        when(orderDao.existsTokenForTenant(eq(TENANT_ID), any())).thenReturn(false);

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
        when(menuService.get(5L, TENANT_ID)).thenReturn(availableItem(5L, "Samosa", new BigDecimal("30.00")));
        when(orderDao.existsTokenForTenant(eq(TENANT_ID), any())).thenReturn(false);
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
}
