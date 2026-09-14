package com.bitesite.service;

import com.bitesite.dao.OrderDao;
import com.bitesite.dao.OrderRefundDao;
import com.bitesite.dao.PaymentDao;
import com.bitesite.exception.InvalidOrderStateException;
import com.bitesite.model.Order;
import com.bitesite.model.OrderItem;
import com.bitesite.model.OrderRefund;
import com.bitesite.model.OrderStatus;
import com.bitesite.model.Payment;
import com.bitesite.model.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundLedgerTest {

    @Mock private PaymentDao paymentDao;
    @Mock private OrderDao orderDao;
    @Mock private OrderRefundDao orderRefundDao;
    @Mock private BillingService billingService;

    private RefundLedger refundLedger;

    @BeforeEach
    void setUp() {
        refundLedger = new RefundLedger(paymentDao, orderDao, orderRefundDao, billingService);
    }

    private OrderItem item(Long id, BigDecimal price) {
        return OrderItem.builder()
                .id(id)
                .quantity(1)
                .unitPrice(price)
                .subtotal(price)
                .build();
    }

    @Test
    void claimItemCancellationThrowsWhenPaymentNotRefundable() {
        Payment payment = Payment.builder()
                .id(1L)
                .status(PaymentStatus.REFUND_PENDING)
                .amount(new BigDecimal("100.00"))
                .build();
        Order order = Order.builder().id(10L).tenantId(1L).status(OrderStatus.PAID).build();

        when(paymentDao.lockByOrderId(10L, 1L)).thenReturn(Optional.of(payment));
        when(orderDao.lockByIdAndTenantId(10L, 1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> refundLedger.claimItemCancellation(10L, 1L, List.of(1L), "reason", "refundReason", 2L))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("already in progress");
    }

    @Test
    void claimItemCancellationLocksAndWritesPendingRefundAndRestates() {
        Payment payment = Payment.builder()
                .id(1L)
                .status(PaymentStatus.CAPTURED)
                .amount(new BigDecimal("200.00"))
                .refundedAmount(BigDecimal.ZERO)
                .razorpayPaymentId("pay_123")
                .build();

        OrderItem item1 = item(101L, new BigDecimal("100.00"));
        OrderItem item2 = item(102L, new BigDecimal("100.00"));
        Order order = Order.builder()
                .id(10L)
                .tenantId(1L)
                .status(OrderStatus.PAID)
                .totalAmount(new BigDecimal("200.00"))
                .items(List.of(item1, item2))
                .build();

        when(paymentDao.lockByOrderId(10L, 1L)).thenReturn(Optional.of(payment));
        when(orderDao.lockByIdAndTenantId(10L, 1L)).thenReturn(Optional.of(order));

        BillingService.Restatement restatement = new BillingService.Restatement(
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00"), new BigDecimal("100.00"));
        when(billingService.withoutLines(order, new BigDecimal("100.00"))).thenReturn(restatement);

        OrderRefund savedRefund = OrderRefund.builder().id(999L).build();
        when(orderRefundDao.insertPending(any(OrderRefund.class))).thenReturn(savedRefund);
        when(orderDao.cancelLines(10L, List.of(101L), "reason", 999L)).thenReturn(1);

        RefundLedger.ItemClaim claim = refundLedger.claimItemCancellation(
                10L, 1L, List.of(101L), "reason", "refundReason", 2L);

        assertThat(claim.refundId()).isEqualTo(999L);
        assertThat(claim.refund()).isEqualByComparingTo("100.00");
        assertThat(claim.paymentId()).isEqualTo(1L);
        assertThat(claim.gatewayPaymentId()).isEqualTo("pay_123");

        verify(paymentDao).reservePartialRefund(1L, new BigDecimal("100.00"));
        verify(orderDao).cancelLines(10L, List.of(101L), "reason", 999L);
        verify(orderDao).restateAmounts(10L, 1L, new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00"));
    }

    @Test
    void failPartialRefundReleasesReservationAndFlagsPayment() {
        OrderRefund refund = OrderRefund.builder()
                .id(999L)
                .paymentId(1L)
                .amount(new BigDecimal("50.00"))
                .build();

        when(orderRefundDao.markFailed(999L, "rfnd_fail")).thenReturn(true);

        boolean result = refundLedger.failPartialRefund(refund, "rfnd_fail", "Gateway failed");

        assertThat(result).isTrue();
        verify(paymentDao).releasePartialRefund(1L, new BigDecimal("50.00"));
        verify(paymentDao).flagForReconciliation(1L, "Gateway failed");
    }
}
