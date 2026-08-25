package com.bitesite.dao;

import com.bitesite.model.Order;
import com.bitesite.model.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface OrderDao {
    /** Inserts the order row plus every {@code OrderItem} on it, atomically. */
    Order createOrder(Order order);

    Optional<Order> findByIdAndTenantId(Long id, Long tenantId);

    /** Orders visible to the canteen kitchen: PAID, PREPARING, READY_FOR_PICKUP, oldest first. */
    List<Order> findKitchenQueue(Long tenantId, Long outletId);

    List<Order> findByUserId(Long userId, Long tenantId);

    void updateStatus(Long id, Long tenantId, OrderStatus status);

    /** Cancels an order and records why, in one statement, so the student's order page can
     * say what happened instead of just showing a red badge. */
    void cancel(Long id, Long tenantId, String reason);

    /**
     * How many of each menu item this outlet has committed to today —
     * menuItemId → total quantity. Backs the per-item daily cap.
     *
     * <p>Counts AWAITING_PAYMENT too. An order at the payment screen has a real claim on
     * the last two dosas, and the expiry sweep releases it a few minutes later if the
     * payment never lands; counting only paid orders would let a queue of half-finished
     * checkouts all be told there is stock left.
     *
     * <p>Takes no cutoff parameter on purpose. {@code orders.created_at} is written by the
     * database ({@code DEFAULT CURRENT_TIMESTAMP}), so the boundary of "today" has to be
     * computed there too. Passing a {@code LocalDateTime} midnight from Java instead makes
     * the window silently wrong by the database server's UTC offset, because the JDBC
     * driver converts outbound timestamp parameters into the zone named by
     * {@code serverTimezone} in the connection URL while {@code CURRENT_TIMESTAMP} ignores
     * it entirely. With the app's {@code serverTimezone=UTC} against a database running in
     * IST, "since midnight" resolved to 18:30 the previous day.
     *
     * <p>Residual assumption: the database server's clock is on the canteen's local day.
     * That already holds for every other date in the app and there is no configured
     * business timezone to check it against.
     */
    Map<Long, Integer> sumQuantitiesByMenuItemToday(Long tenantId, Long outletId);

    /** Whether this tenant has already issued this token <em>today</em>. Scoped to the day
     * because that is the only window in which a token has to be unambiguous — see
     * V13__daily_order_tokens.sql for why lifetime uniqueness could not hold. */
    boolean existsTokenForTenantToday(Long tenantId, String token);

    /**
     * Platform-wide token lookup for the support desk. Deliberately not tenant-scoped:
     * a super admin holds no tenantId, and a student handing over a token does not know
     * which tenant they belong to. Callers must gate this on the admin role — every
     * other order read in the app goes through a tenant-scoped finder.
     */
    List<Order> searchByTokenAcrossTenants(String token);

    /** Orders still AWAITING_PAYMENT for longer than {@code timeoutMinutes} — used by the
     * expiry sweep. Takes the timeout rather than a cutoff instant for the same reason as
     * {@link #sumQuantitiesByMenuItemToday}: the comparison has to be made against the
     * database's own clock, since that is what wrote {@code created_at}. */
    List<Order> findExpiredAwaitingPayment(int timeoutMinutes);
}
