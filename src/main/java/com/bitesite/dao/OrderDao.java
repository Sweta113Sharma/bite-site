package com.bitesite.dao;

import com.bitesite.model.Order;
import com.bitesite.model.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderDao {
    /** Inserts the order row plus every {@code OrderItem} on it, atomically. */
    Order createOrder(Order order);

    Optional<Order> findByIdAndTenantId(Long id, Long tenantId);

    /** Orders visible to the canteen kitchen: PAID, PREPARING, READY_FOR_PICKUP, oldest first. */
    List<Order> findKitchenQueue(Long tenantId, Long outletId);

    List<Order> findByUserId(Long userId, Long tenantId);

    void updateStatus(Long id, Long tenantId, OrderStatus status);

    boolean existsTokenForTenant(Long tenantId, String token);

    /** Orders still AWAITING_PAYMENT past the payment timeout — used by the expiry sweep. */
    List<Order> findExpiredAwaitingPayment(LocalDateTime cutoff);
}
