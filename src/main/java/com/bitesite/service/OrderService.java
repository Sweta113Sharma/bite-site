package com.bitesite.service;

import com.bitesite.dao.OrderDao;
import com.bitesite.dao.PaymentDao;
import com.bitesite.dto.CheckoutResult;
import com.bitesite.dto.GatewayOrder;
import com.bitesite.exception.InvalidOrderStateException;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.MenuItem;
import com.bitesite.model.Order;
import com.bitesite.model.OrderItem;
import com.bitesite.model.OrderStatus;
import com.bitesite.model.Outlet;
import com.bitesite.model.Payment;
import com.bitesite.model.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_GENERATION_ATTEMPTS = 10;
    /** Matches the orders.cancellation_reason column width. */
    private static final int MAX_REASON_LENGTH = 200;
    private static final int PICKUP_CODE_ATTEMPTS = 10;

    private final OrderDao orderDao;
    private final PaymentDao paymentDao;
    private final MenuService menuService;
    private final OutletService outletService;
    private final PaymentGateway paymentGateway;
    private final AuditService auditService;
    private final OrderNotifier orderNotifier;

    /**
     * Builds the order from the cart (re-pricing every line from the database, never
     * trusting anything cached client-side), persists it as AWAITING_PAYMENT, then starts
     * a payment-gateway order. The order row commits on its own short transaction before
     * the external gateway call runs, so a slow/failed network call never holds a DB
     * transaction open.
     */
    public CheckoutResult checkout(Long tenantId, Long outletId, Long userId, Map<Long, Integer> cartQuantities) {
        if (cartQuantities.isEmpty()) {
            throw new InvalidOrderStateException("Your cart is empty.");
        }

        requireOutletOpen(tenantId, outletId);

        // One roll-up for the whole cart rather than a query per line.
        //
        // Best-effort under concurrency: this count is read here and acted on at
        // createOrder() below, with no lock in between, so two students checking out the
        // last dosa in the same instant can both pass. A cap can therefore be overshot by
        // at most the quantities of the checkouts in flight at that moment.
        //
        // Making it strict is possible and would not span the gateway call — that happens
        // after the order row is written. It needs the read and the insert inside one
        // transaction with a locking read (SELECT ... FOR UPDATE) on the capped items,
        // extracted so the transaction still closes before paymentGateway.createOrder().
        // The cost is serialising concurrent checkouts of the same item for the duration of
        // two local queries. Left best-effort deliberately: at canteen volumes the overshoot
        // is a dosa or two on a busy item, and a canteen can hand that back far more easily
        // than it can absorb a lock-contention bug in the payment path.
        Map<Long, Integer> soldToday = menuService.soldToday(tenantId, outletId);

        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> entry : cartQuantities.entrySet()) {
            MenuItem item = menuService.get(entry.getKey(), tenantId);
            if (!item.isAvailable() || !item.getOutletId().equals(outletId)) {
                throw new InvalidOrderStateException(item.getName() + " is no longer available.");
            }
            item.setSoldToday(soldToday.getOrDefault(item.getId(), 0));
            requireDailyCapacity(item, entry.getValue());
            BigDecimal unitPrice = item.effectivePrice();
            BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(entry.getValue()));
            total = total.add(subtotal);
            orderItems.add(OrderItem.builder()
                    .menuItemId(item.getId())
                    .itemNameSnapshot(item.getName())
                    .quantity(entry.getValue())
                    .unitPrice(unitPrice)
                    .subtotal(subtotal)
                    .build());
        }

        Order order = Order.builder()
                .tenantId(tenantId)
                .outletId(outletId)
                .userId(userId)
                .tokenNo(generateUniqueToken(tenantId))
                .totalAmount(total)
                .status(OrderStatus.AWAITING_PAYMENT)
                .items(orderItems)
                .build();
        Order saved = orderDao.createOrder(order);

        GatewayOrder gatewayOrder;
        try {
            gatewayOrder = paymentGateway.createOrder(total, saved.getTokenNo());
        } catch (RuntimeException e) {
            orderDao.updateStatus(saved.getId(), tenantId, OrderStatus.PAYMENT_FAILED);
            throw e;
        }

        Payment payment = Payment.builder()
                .tenantId(tenantId)
                .orderId(saved.getId())
                .razorpayOrderId(gatewayOrder.gatewayOrderId())
                .amount(total)
                .status(PaymentStatus.CREATED)
                .build();
        try {
            paymentDao.save(payment);
        } catch (RuntimeException e) {
            // The dangerous window. A gateway order now exists and is payable, but we have
            // no local row tying it to this order — so if the student went on to pay,
            // confirmPayment would look the gateway id up, find nothing, and throw: money
            // taken, order never marked paid, and nothing anywhere pointing at the two.
            // Failing the order closes the window: the student never reaches the pay
            // screen, and the unpaid gateway order is simply abandoned.
            log.error("Payment row could not be saved for order {} after gateway order {} was created; "
                    + "failing the order so it cannot be paid against a missing record",
                    saved.getId(), gatewayOrder.gatewayOrderId(), e);
            orderDao.updateStatus(saved.getId(), tenantId, OrderStatus.PAYMENT_FAILED);
            throw e;
        }

        return new CheckoutResult(saved, gatewayOrder);
    }

    /**
     * Both of the outlet's own "closed" states, checked at the one point that matters. The
     * student UI hides the Add button for either, but the cart survives in the session
     * across the moment staff flip the switch, so the guard has to live here too.
     */
    private void requireOutletOpen(Long tenantId, Long outletId) {
        Outlet outlet = outletService.get(outletId, tenantId);
        if (!outlet.isActive()) {
            throw new InvalidOrderStateException(outlet.getName() + " is no longer taking orders.");
        }
        if (!outlet.isAcceptingOrders()) {
            throw new InvalidOrderStateException(
                    outlet.getName() + " has paused new orders for now. Please try again shortly.");
        }
    }

    private void requireDailyCapacity(MenuItem item, int wanted) {
        Integer remaining = item.remainingToday();
        if (remaining == null || wanted <= remaining) {
            return;
        }
        throw new InvalidOrderStateException(remaining == 0
                ? item.getName() + " is sold out for today."
                : "Only " + remaining + " left of " + item.getName() + " today.");
    }

    private String generateUniqueToken(Long tenantId) {
        for (int attempt = 0; attempt < TOKEN_GENERATION_ATTEMPTS; attempt++) {
            String candidate = "BITE-" + (1000 + RANDOM.nextInt(9000));
            if (!orderDao.existsTokenForTenantToday(tenantId, candidate)) {
                return candidate;
            }
        }
        // Only reachable if one tenant issues thousands of tokens in a single day: ten
        // misses against a same-day pool of 9,000 is (N/9000)^10, which is about one in
        // 4x10^12 at 500 orders a day.
        throw new IllegalStateException("Could not generate a unique order token");
    }

    public Order getForUser(Long orderId, Long userId, Long tenantId) {
        Order order = getForTenant(orderId, tenantId);
        if (!order.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Order not found");
        }
        return order;
    }

    public Order getForTenant(Long orderId, Long tenantId) {
        return orderDao.findByIdAndTenantId(orderId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    }

    public List<Order> historyForUser(Long userId, Long tenantId) {
        return orderDao.findByUserId(userId, tenantId);
    }

    /**
     * Unfinished orders, most urgent first — see {@link OrderStatus#attentionRank()}.
     * Drives the active-order strip and the orders screen's "Active now" group.
     */
    public List<Order> liveForUser(Long userId, Long tenantId) {
        return orderDao.findLiveByUserId(userId, tenantId).stream()
                .sorted(Comparator.comparingInt((Order o) -> o.getStatus().attentionRank())
                        .thenComparing(Order::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public List<Order> kitchenQueue(Long tenantId, Long outletId) {
        return orderDao.findKitchenQueue(tenantId, outletId);
    }

    public Payment getPaymentForOrder(Long orderId, Long tenantId) {
        return findPaymentForOrder(orderId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
    }

    /** Same lookup for callers that treat "no payment" as a normal state rather than an
     * error — an order can be cancelled before one was ever created. */
    public Optional<Payment> findPaymentForOrder(Long orderId, Long tenantId) {
        return paymentDao.findByOrderId(orderId, tenantId);
    }

    /**
     * Called by both the client-side checkout success callback and the async webhook —
     * idempotent so whichever arrives first wins and the other is a no-op.
     *
     * <p>{@code signature} is the per-payment signature Razorpay Checkout.js hands the
     * browser on success (order_id + "|" + payment_id, HMAC-signed) — pass it when called
     * from that path and it is verified here before anything is marked paid. The webhook
     * path has no equivalent per-payment signature (Razorpay signs the whole webhook body
     * instead, over a different header the caller must verify itself before calling this
     * method); pass {@code null} from there rather than fabricate a value to check.
     */
    @Transactional
    public boolean confirmPayment(String gatewayOrderId, String gatewayPaymentId, String signature) {
        Payment payment = paymentDao.findByRazorpayOrderId(gatewayOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            return true;
        }

        if (signature != null && !paymentGateway.verifyPaymentSignature(gatewayOrderId, gatewayPaymentId, signature)) {
            paymentDao.updateStatus(payment.getId(), PaymentStatus.FAILED);
            return false;
        }

        paymentDao.markVerified(payment.getId(), gatewayPaymentId, signature, PaymentStatus.CAPTURED);
        Order order = getForTenant(payment.getOrderId(), payment.getTenantId());
        if (!order.getStatus().canTransitionTo(OrderStatus.PAID)) {
            // Money we hold against an order that cannot be honoured — a cancellation, or
            // an order already completed. Previously this branch did not exist: the
            // transition silently failed and confirmPayment returned success, so the
            // student was charged and nothing anywhere recorded it. It is a refund, and a
            // refund needs a person.
            String reason = "Captured while order was " + order.getStatus() + "; needs refund";
            paymentDao.flagForReconciliation(payment.getId(), reason);
            auditService.record(null, order.getTenantId(), "Payment", payment.getId(),
                    "CAPTURE_UNAPPLIED", order.getStatus(), null);
            log.error("Payment {} captured for order {} which is {} — flagged for refund",
                    payment.getId(), order.getId(), order.getStatus());
            return true;
        }

        if (order.getStatus() == OrderStatus.EXPIRED) {
            // The common late capture: the sweeper expired the order while the student was
            // still on their bank's page. They paid, so they get their food.
            log.info("Reviving expired order {} — payment {} captured after the timeout",
                    order.getId(), payment.getId());
            auditService.record(null, order.getTenantId(), "Order", order.getId(),
                    "REVIVED_ON_LATE_PAYMENT", OrderStatus.EXPIRED, OrderStatus.PAID);
        }

        orderDao.updateStatus(order.getId(), order.getTenantId(), OrderStatus.PAID);
            // Doubles as the receipt. Payment succeeded and the only acknowledgement was
            // the page the student happened to be looking at — nothing they could keep,
            // and nothing at all if the browser closed on the redirect back from Razorpay.
        orderNotifier.notifyOrderUpdate(order.getUserId(), "Order confirmed",
                "We have your payment for order " + order.getTokenNo() + ". The canteen is on it — "
                        + "you will hear from us again when it is ready to collect.");
        return true;
    }

    public void advanceStatus(Long orderId, Long tenantId, OrderStatus newStatus, Long actorUserId) {
        Order order = getForTenant(orderId, tenantId);
        if (!order.getStatus().canTransitionTo(newStatus)) {
            throw new InvalidOrderStateException(
                    "Cannot move order from " + order.getStatus() + " to " + newStatus);
        }
        orderDao.updateStatus(orderId, tenantId, newStatus);
        auditService.record(actorUserId, tenantId, "Order", orderId, "STATUS_" + newStatus,
                order.getStatus(), newStatus);
        if (newStatus == OrderStatus.READY_FOR_PICKUP) {
            String code = issuePickupCode(orderId, tenantId, order.getOutletId());
            orderNotifier.notifyOrderUpdate(order.getUserId(), "Order ready for pickup",
                    "Your order " + order.getTokenNo() + " is ready — show code " + code + " at the counter.");
        }
    }

    /**
     * Issues the code a student shows at the counter. Generated here rather than at
     * checkout so it exists only while the order is actually on the ready shelf — a
     * screenshot taken earlier carries nothing usable.
     *
     * <p>Uniqueness is only enforced against the codes currently live at this outlet,
     * because that is the only set a counter can confuse. Across outlets, or across days,
     * a repeat is meaningless.
     */
    private String issuePickupCode(Long orderId, Long tenantId, Long outletId) {
        Set<String> taken = new HashSet<>(orderDao.findActivePickupCodes(tenantId, outletId));
        String code = null;
        for (int attempt = 0; attempt < PICKUP_CODE_ATTEMPTS && code == null; attempt++) {
            String candidate = String.format("%04d", RANDOM.nextInt(10000));
            if (!taken.contains(candidate)) {
                code = candidate;
            }
        }
        if (code == null) {
            // 10 collisions means ~10k orders are simultaneously awaiting collection at one
            // counter, which is not a real canteen. Failing loudly beats issuing a duplicate.
            throw new IllegalStateException("Could not issue a unique pickup code");
        }
        orderDao.setPickupCode(orderId, tenantId, code);
        return code;
    }

    /**
     * Marks an order collected, but only against the code the student is showing.
     *
     * <p>This is the authentication event for handover. Before it, staff marked orders
     * collected with a bare button and the only thing linking a person to an order was a
     * token number on a screen — which is screenshot-shareable and gave no answer at all
     * to "I never received it".
     */
    public void completeWithPickupCode(Long orderId, Long tenantId, String submittedCode, Long actorUserId) {
        Order order = getForTenant(orderId, tenantId);
        if (order.getStatus() != OrderStatus.READY_FOR_PICKUP) {
            throw new InvalidOrderStateException("Only an order waiting for collection can be handed over.");
        }
        String expected = order.getPickupCode();
        String given = submittedCode == null ? "" : submittedCode.trim();
        // Orders that reached ready before this feature shipped have no code; they cannot
        // be held hostage to one, so those fall back to the old bare confirmation.
        if (expected != null && !expected.equals(given)) {
            auditService.record(actorUserId, tenantId, "Order", orderId, "PICKUP_CODE_REJECTED", expected != null, false);
            throw new InvalidOrderStateException("That code doesn't match this order. Check the student's screen.");
        }
        advanceStatus(orderId, tenantId, OrderStatus.COMPLETED, actorUserId);
    }

    /**
     * Cancels an order, refunding it first if a payment was actually captured. The refund
     * call must succeed before anything in our own database changes — never mark an order
     * cancelled while leaving the customer's money uncollected from a refund that silently
     * failed. Orders that never reached PAID (AWAITING_PAYMENT, PAYMENT_FAILED) have nothing
     * captured, so those are cancelled directly with no refund call.
     */
    @Transactional
    public void cancelOrder(Long orderId, Long tenantId, Long actorUserId, String reason) {
        Order order = getForTenant(orderId, tenantId);
        if (!order.getStatus().canTransitionTo(OrderStatus.CANCELLED)) {
            throw new InvalidOrderStateException(
                    "Cannot cancel an order in " + order.getStatus() + " status.");
        }

        if (order.getStatus() == OrderStatus.PAID) {
            Payment payment = getPaymentForOrder(orderId, tenantId);
            if (payment.getStatus() == PaymentStatus.CAPTURED) {
                paymentGateway.refund(payment.getRazorpayPaymentId(), payment.getAmount());
                paymentDao.updateStatus(payment.getId(), PaymentStatus.REFUNDED);
            }
        }

        OrderStatus previous = order.getStatus();
        String explanation = normalizeReason(reason);
        orderDao.cancel(orderId, tenantId, explanation);
        auditService.record(actorUserId, tenantId, "Order", orderId, "STATUS_CANCELLED", previous, OrderStatus.CANCELLED);
        orderNotifier.notifyOrderUpdate(order.getUserId(), "Order cancelled",
                "Your order " + order.getTokenNo() + " was cancelled" + (previous == OrderStatus.PAID ? " and refunded" : "")
                        + ": " + explanation);
    }

    /**
     * Staff can skip the reason box; the student still gets a sentence rather than a blank.
     * Clamped to the width of orders.cancellation_reason here rather than at each caller,
     * so no entry point can hand the database a string it will refuse.
     */
    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Cancelled by the canteen";
        }
        String trimmed = reason.trim();
        return trimmed.length() <= MAX_REASON_LENGTH ? trimmed : trimmed.substring(0, MAX_REASON_LENGTH);
    }

    /**
     * Platform-side manual refund, for the case the refund policy describes: an order
     * that is already past PREPARING, where the student "raises it through Support so
     * canteen/admin staff can review and refund it manually if appropriate".
     *
     * <p>{@link #cancelOrder} cannot serve this. The state machine deliberately forbids
     * PREPARING and READY_FOR_PICKUP from reaching CANCELLED, so an order that the
     * kitchen has started is unrefundable through the normal path — which is exactly
     * the situation support is called about.
     *
     * <p>Refunding and cancelling are separated here because they are different facts.
     * The refund is about money and always happens; the cancellation is about
     * fulfilment and only happens if the food was never handed over. A COMPLETED order
     * that gets refunded stays COMPLETED, because it was in fact completed.
     *
     * <p>Gateway first, as in {@link #cancelOrder}: nothing in our database moves until
     * the money is actually on its way back.
     */
    @Transactional
    public void refundOrder(Long orderId, Long tenantId, Long actorUserId, String reason) {
        Order order = getForTenant(orderId, tenantId);
        Payment payment = getPaymentForOrder(orderId, tenantId);

        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            throw new InvalidOrderStateException("This order has already been refunded.");
        }
        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            throw new InvalidOrderStateException(
                    "Only a captured payment can be refunded; this one is " + payment.getStatus() + ".");
        }

        paymentGateway.refund(payment.getRazorpayPaymentId(), payment.getAmount());
        paymentDao.updateStatus(payment.getId(), PaymentStatus.REFUNDED);
        auditService.record(actorUserId, tenantId, "Payment", payment.getId(), "REFUND_MANUAL",
                PaymentStatus.CAPTURED, PaymentStatus.REFUNDED);

        // Only orders that were never handed over stop being live. COMPLETED stays put.
        OrderStatus previous = order.getStatus();
        if (previous != OrderStatus.COMPLETED) {
            orderDao.cancel(orderId, tenantId, normalizeReason(reason));
            auditService.record(actorUserId, tenantId, "Order", orderId, "CANCELLED_BY_REFUND",
                    previous, OrderStatus.CANCELLED);
        }

        orderNotifier.notifyOrderUpdate(order.getUserId(), "Refund issued",
                "Your order " + order.getTokenNo() + " has been refunded. It should reach your account in 5-7 days.");
        log.info("Manual refund on order {} (tenant {}) by user {}: {}", orderId, tenantId, actorUserId, reason);
    }

    /** Sweeps unpaid orders past the payment timeout so they stop counting as pending
     * demand; call periodically (see {@link com.bitesite.config.OrderExpiryScheduler}). */
    public int expireStalePayments(int timeoutMinutes) {
        List<Order> stale = orderDao.findExpiredAwaitingPayment(timeoutMinutes);
        for (Order order : stale) {
            orderDao.updateStatus(order.getId(), order.getTenantId(), OrderStatus.EXPIRED);
        }
        return stale.size();
    }
}
