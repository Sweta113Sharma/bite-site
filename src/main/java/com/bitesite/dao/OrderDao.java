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

    /**
     * A student's orders that are not finished. Read on every customer page render for
     * the active-order strip, so it filters in SQL rather than loading the full history
     * and discarding most of it.
     */
    List<Order> findLiveByUserId(Long userId, Long tenantId);

    /**
     * One outlet's orders, newest first, optionally narrowed to a single status.
     * Bounded by an explicit limit rather than paged — the same shape AuditLogDao uses,
     * and the only bounding convention this codebase has.
     *
     * @param status null for every status
     */
    List<Order> findByOutlet(Long tenantId, Long outletId, OrderStatus status, int limit, int offset);

    /**
     * Per-day totals for one outlet, newest day first: how many orders and how much money.
     *
     * <p>Grouped on {@code token_day}, the stored generated DATE(created_at) column, rather
     * than on created_at directly. That column is materialised and already indexed, and
     * critically it keeps the day boundary inside the database — see the note on
     * {@link #sumQuantitiesByMenuItemToday} for why a Java-side date here would be wrong.
     */
    List<DailySales> dailySales(Long tenantId, Long outletId, int days);

    /** One row of {@link #dailySales}. Cancelled, expired and failed orders are excluded:
     * this is a sales report, not an activity log. */
    record DailySales(java.time.LocalDate day, int orderCount, java.math.BigDecimal revenue) {}

    void updateStatus(Long id, Long tenantId, OrderStatus status);

    /** Cancels an order and records why, in one statement, so the student's order page can
     * say what happened instead of just showing a red badge. */
    void cancel(Long id, Long tenantId, String reason);

    /** Stores the pickup code issued when an order is marked ready. */
    void setPickupCode(Long id, Long tenantId, String code);

    /** Codes currently in use on the ready shelf at one outlet — the only window in which
     * two identical codes could be confused at the counter. */
    List<String> findActivePickupCodes(Long tenantId, Long outletId);

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

    /**
     * Recent orders across every tenant, newest first, optionally narrowed by tenant or
     * status. Admin console only — same gating contract as the finder above.
     *
     * @param tenantId null for every tenant
     * @param status   null for every status
     */
    /** Ask for one row more than the page needs; Paged.of uses the extra to decide whether
     * a next page exists without a second COUNT over the same predicate.
     *
     * <p>{@code search} matches an order token or the student's email, which is what a
     * support conversation actually gives you. Null or blank means no text filter. */
    List<Order> findRecentAcrossTenants(Long tenantId, OrderStatus status, String search, int limit, int offset);

    /** Orders still AWAITING_PAYMENT for longer than {@code timeoutMinutes} — used by the
     * expiry sweep. Takes the timeout rather than a cutoff instant for the same reason as
     * {@link #sumQuantitiesByMenuItemToday}: the comparison has to be made against the
     * database's own clock, since that is what wrote {@code created_at}. */
    List<Order> findExpiredAwaitingPayment(int timeoutMinutes);
}
