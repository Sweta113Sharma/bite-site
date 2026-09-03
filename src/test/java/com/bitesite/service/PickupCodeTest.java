package com.bitesite.service;

import com.bitesite.dao.OrderDao;
import com.bitesite.dao.PaymentDao;
import com.bitesite.exception.InvalidOrderStateException;
import com.bitesite.model.Order;
import com.bitesite.model.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Handover authentication: the code is issued at ready and required at collection. */
@ExtendWith(MockitoExtension.class)
class PickupCodeTest {

    @Mock private OrderDao orderDao;
    @Mock private PaymentDao paymentDao;
    @Mock private MenuService menuService;
    @Mock private OutletService outletService;
    @Mock private PaymentGateway paymentGateway;
    @Mock private AuditService auditService;
    @Mock private OrderNotifier orderNotifier;

    private OrderService orderService;

    private static final Long TENANT = 1L, OUTLET = 10L, USER = 100L, ORDER = 42L;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderDao, paymentDao, menuService, outletService,
                paymentGateway, auditService, orderNotifier);
    }

    private Order at(OrderStatus status, String code) {
        return Order.builder().id(ORDER).tenantId(TENANT).outletId(OUTLET).userId(USER)
                .tokenNo("BITE-1234").totalAmount(BigDecimal.TEN).status(status).pickupCode(code).build();
    }

    @Test
    void markingAnOrderReadyIssuesAFourDigitCode() {
        when(orderDao.findByIdAndTenantId(ORDER, TENANT)).thenReturn(Optional.of(at(OrderStatus.PREPARING, null)));
        when(orderDao.findActivePickupCodes(TENANT, OUTLET)).thenReturn(List.of());

        orderService.advanceStatus(ORDER, TENANT, OrderStatus.READY_FOR_PICKUP, USER);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(orderDao).setPickupCode(eq(ORDER), eq(TENANT), code.capture());
        assertThat(code.getValue()).matches("\\d{4}");
    }

    @Test
    void theIssuedCodeIsNeverOneAlreadyOnTheReadyShelf() {
        when(orderDao.findByIdAndTenantId(ORDER, TENANT)).thenReturn(Optional.of(at(OrderStatus.PREPARING, null)));
        List<String> taken = new java.util.ArrayList<>();
        for (int i = 0; i < 500; i++) {
            taken.add(String.format("%04d", i));
        }
        when(orderDao.findActivePickupCodes(TENANT, OUTLET)).thenReturn(taken);

        orderService.advanceStatus(ORDER, TENANT, OrderStatus.READY_FOR_PICKUP, USER);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(orderDao).setPickupCode(eq(ORDER), eq(TENANT), code.capture());
        assertThat(taken).doesNotContain(code.getValue());
    }

    @Test
    void itFailsLoudlyRatherThanIssuingADuplicateWhenEveryCodeIsTaken() {
        // Not a real canteen — 10,000 orders awaiting collection at one counter — but the
        // guard matters: a duplicate code would hand one student another's order.
        when(orderDao.findByIdAndTenantId(ORDER, TENANT)).thenReturn(Optional.of(at(OrderStatus.PREPARING, null)));
        List<String> all = new java.util.ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            all.add(String.format("%04d", i));
        }
        when(orderDao.findActivePickupCodes(TENANT, OUTLET)).thenReturn(all);

        assertThatThrownBy(() -> orderService.advanceStatus(ORDER, TENANT, OrderStatus.READY_FOR_PICKUP, USER))
                .isInstanceOf(IllegalStateException.class);
        verify(orderDao, never()).setPickupCode(anyLong(), anyLong(), anyString());
    }

    @Test
    void theStudentIsToldTheCodeInTheReadyNotification() {
        when(orderDao.findByIdAndTenantId(ORDER, TENANT)).thenReturn(Optional.of(at(OrderStatus.PREPARING, null)));
        when(orderDao.findActivePickupCodes(TENANT, OUTLET)).thenReturn(List.of());

        orderService.advanceStatus(ORDER, TENANT, OrderStatus.READY_FOR_PICKUP, USER);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(orderNotifier).notifyOrderUpdate(eq(USER), anyString(), body.capture());
        assertThat(body.getValue()).containsPattern("\\d{4}");
    }

    @Test
    void aWrongCodeIsRefusedAndTheOrderIsNotHandedOver() {
        when(orderDao.findByIdAndTenantId(ORDER, TENANT))
                .thenReturn(Optional.of(at(OrderStatus.READY_FOR_PICKUP, "4321")));

        assertThatThrownBy(() -> orderService.completeWithPickupCode(ORDER, TENANT, "1234", USER))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("doesn't match");

        verify(orderDao, never()).updateStatus(anyLong(), anyLong(), eq(OrderStatus.COMPLETED));
        // A rejected attempt is recorded — repeated failures at one counter are worth seeing.
        verify(auditService).record(eq(USER), eq(TENANT), eq("Order"), eq(ORDER),
                eq("PICKUP_CODE_REJECTED"), any(), any());
    }

    @Test
    void theRightCodeHandsTheOrderOver() {
        when(orderDao.findByIdAndTenantId(ORDER, TENANT))
                .thenReturn(Optional.of(at(OrderStatus.READY_FOR_PICKUP, "4321")));

        orderService.completeWithPickupCode(ORDER, TENANT, "4321", USER);

        verify(orderDao).updateStatus(ORDER, TENANT, OrderStatus.COMPLETED);
    }

    @Test
    void surroundingWhitespaceIsForgiven() {
        when(orderDao.findByIdAndTenantId(ORDER, TENANT))
                .thenReturn(Optional.of(at(OrderStatus.READY_FOR_PICKUP, "4321")));

        orderService.completeWithPickupCode(ORDER, TENANT, "  4321 ", USER);

        verify(orderDao).updateStatus(ORDER, TENANT, OrderStatus.COMPLETED);
    }

    @Test
    void anOrderThatPredatesPickupCodesCanStillBeHandedOver() {
        // Orders already on the shelf when this shipped have no code and must not be stuck.
        when(orderDao.findByIdAndTenantId(ORDER, TENANT))
                .thenReturn(Optional.of(at(OrderStatus.READY_FOR_PICKUP, null)));

        orderService.completeWithPickupCode(ORDER, TENANT, "", USER);

        verify(orderDao).updateStatus(ORDER, TENANT, OrderStatus.COMPLETED);
    }

    @Test
    void anOrderNotYetReadyCannotBeCollected() {
        when(orderDao.findByIdAndTenantId(ORDER, TENANT))
                .thenReturn(Optional.of(at(OrderStatus.PREPARING, null)));

        assertThatThrownBy(() -> orderService.completeWithPickupCode(ORDER, TENANT, "1234", USER))
                .isInstanceOf(InvalidOrderStateException.class);
        verify(orderDao, never()).updateStatus(anyLong(), anyLong(), any());
    }
}
