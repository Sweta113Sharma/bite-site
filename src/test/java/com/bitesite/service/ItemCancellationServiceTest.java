package com.bitesite.service;

import com.bitesite.dao.OrderRefundDao;
import com.bitesite.dto.GatewayRefund;
import com.bitesite.exception.InvalidOrderStateException;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.ItemCancelReason;
import com.bitesite.model.Order;
import com.bitesite.model.OrderItem;
import com.bitesite.model.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemCancellationServiceTest {

    @Mock private OrderService orderService;
    @Mock private RefundLedger refundLedger;
    @Mock private OrderRefundDao orderRefundDao;
    @Mock private PaymentGateway paymentGateway;
    @Mock private MenuService menuService;
    @Mock private AuditService auditService;
    @Mock private OrderNotifier orderNotifier;

    private ItemCancellationService itemCancellationService;

    @BeforeEach
    void setUp() {
        itemCancellationService = new ItemCancellationService(
                orderService, refundLedger, orderRefundDao, paymentGateway,
                menuService, auditService, orderNotifier);
    }

    private Order sampleOrder(OrderStatus status, Long outletId, List<OrderItem> items) {
        return Order.builder()
                .id(100L)
                .tenantId(1L)
                .outletId(outletId)
                .userId(50L)
                .tokenNo("T-101")
                .status(status)
                .totalAmount(new BigDecimal("200.00"))
                .foodAmount(new BigDecimal("200.00"))
                .items(items)
                .build();
    }

    private OrderItem line(Long id, Long menuItemId, String name, BigDecimal price) {
        return OrderItem.builder()
                .id(id)
                .menuItemId(menuItemId)
                .itemNameSnapshot(name)
                .quantity(1)
                .unitPrice(price)
                .subtotal(price)
                .build();
    }

    @Test
    void rejectsEmptySelection() {
        assertThatThrownBy(() -> itemCancellationService.cancelItems(100L, 1L, 10L, List.of(), ItemCancelReason.CANNOT_MAKE, 1L))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("Tick the items");
    }

    @Test
    void rejectsNullReason() {
        assertThatThrownBy(() -> itemCancellationService.cancelItems(100L, 1L, 10L, List.of(1L), null, 1L))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("Choose why");
    }

    @Test
    void rejectsOrderFromDifferentOutlet() {
        Order order = sampleOrder(OrderStatus.PAID, 20L, List.of(line(1L, 101L, "Burger", new BigDecimal("100.00"))));
        when(orderService.getForTenant(100L, 1L)).thenReturn(order);

        assertThatThrownBy(() -> itemCancellationService.cancelItems(100L, 1L, 10L, List.of(1L), ItemCancelReason.CANNOT_MAKE, 1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rejectsOrderInCompletedStatus() {
        Order order = sampleOrder(OrderStatus.COMPLETED, 10L, List.of(line(1L, 101L, "Burger", new BigDecimal("100.00"))));
        when(orderService.getForTenant(100L, 1L)).thenReturn(order);

        assertThatThrownBy(() -> itemCancellationService.cancelItems(100L, 1L, 10L, List.of(1L), ItemCancelReason.CANNOT_MAKE, 1L))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("Items can only be removed before an order is ready");
    }

    @Test
    void rejectsItemsNotActiveOnOrder() {
        OrderItem active = line(1L, 101L, "Burger", new BigDecimal("100.00"));
        Order order = sampleOrder(OrderStatus.PAID, 10L, List.of(active));
        when(orderService.getForTenant(100L, 1L)).thenReturn(order);

        assertThatThrownBy(() -> itemCancellationService.cancelItems(100L, 1L, 10L, List.of(999L), ItemCancelReason.CANNOT_MAKE, 1L))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("no longer on the order");
    }

    @Test
    void cancellingAllRemainingItemsRoutesToCancelUnmakeable() {
        OrderItem item1 = line(1L, 101L, "Burger", new BigDecimal("100.00"));
        OrderItem item2 = line(2L, 102L, "Fries", new BigDecimal("50.00"));
        Order order = sampleOrder(OrderStatus.PREPARING, 10L, List.of(item1, item2));
        when(orderService.getForTenant(100L, 1L)).thenReturn(order);
        when(menuService.markOutOfStockToday(anyList(), eq(10L), eq(1L), eq(5L))).thenReturn(2);

        ItemCancellationService.Result result = itemCancellationService.cancelItems(
                100L, 1L, 10L, List.of(1L, 2L), ItemCancelReason.OUT_OF_STOCK, 5L);

        assertThat(result.wholeOrderCancelled()).isTrue();
        assertThat(result.markedOutOfStock()).isTrue();
        verify(orderService).cancelUnmakeable(eq(100L), eq(1L), eq(5L), anyString());
        verify(menuService).markOutOfStockToday(List.of(101L, 102L), 10L, 1L, 5L);
        verify(refundLedger, never()).claimItemCancellation(anyLong(), anyLong(), anyList(), anyString(), anyString(), anyLong());
    }

    @Test
    void cancellingPartialItemsClaimsAndRefundsSuccessfully() {
        OrderItem item1 = line(1L, 101L, "Burger", new BigDecimal("100.00"));
        OrderItem item2 = line(2L, 102L, "Fries", new BigDecimal("50.00"));
        Order order = sampleOrder(OrderStatus.PREPARING, 10L, List.of(item1, item2));
        when(orderService.getForTenant(100L, 1L)).thenReturn(order);

        RefundLedger.ItemClaim claim = new RefundLedger.ItemClaim(
                order, List.of(item2), new BigDecimal("50.00"), 555L, 777L, "pay_rzp_123");
        when(refundLedger.claimItemCancellation(eq(100L), eq(1L), eq(List.of(2L)), anyString(), anyString(), eq(5L)))
                .thenReturn(claim);
        when(paymentGateway.refundPart("pay_rzp_123", new BigDecimal("50.00")))
                .thenReturn(new GatewayRefund("rfnd_abc", new BigDecimal("50.00"), "processed"));

        ItemCancellationService.Result result = itemCancellationService.cancelItems(
                100L, 1L, 10L, List.of(2L), ItemCancelReason.CANNOT_MAKE, 5L);

        assertThat(result.wholeOrderCancelled()).isFalse();
        assertThat(result.refund()).isEqualByComparingTo("50.00");
        assertThat(result.refundConfirmed()).isTrue();
        assertThat(result.markedOutOfStock()).isFalse();

        verify(orderRefundDao).markRefunded(555L, "rfnd_abc");
        verify(orderNotifier).notifyOrderUpdate(eq(50L), anyString(), anyString());
    }

    @Test
    void cancellingPartialItemsWhenGatewayFailsFlagsForReconciliation() {
        OrderItem item1 = line(1L, 101L, "Burger", new BigDecimal("100.00"));
        OrderItem item2 = line(2L, 102L, "Fries", new BigDecimal("50.00"));
        Order order = sampleOrder(OrderStatus.PREPARING, 10L, List.of(item1, item2));
        when(orderService.getForTenant(100L, 1L)).thenReturn(order);

        RefundLedger.ItemClaim claim = new RefundLedger.ItemClaim(
                order, List.of(item2), new BigDecimal("50.00"), 555L, 777L, "pay_rzp_123");
        when(refundLedger.claimItemCancellation(eq(100L), eq(1L), eq(List.of(2L)), anyString(), anyString(), eq(5L)))
                .thenReturn(claim);
        when(paymentGateway.refundPart("pay_rzp_123", new BigDecimal("50.00")))
                .thenThrow(new RuntimeException("Connection timed out"));

        ItemCancellationService.Result result = itemCancellationService.cancelItems(
                100L, 1L, 10L, List.of(2L), ItemCancelReason.CANNOT_MAKE, 5L);

        assertThat(result.wholeOrderCancelled()).isFalse();
        assertThat(result.refundConfirmed()).isFalse();

        verify(refundLedger).recordUnresolved(eq(777L), anyString());
        verify(orderRefundDao, never()).markRefunded(anyLong(), anyString());
    }
}
