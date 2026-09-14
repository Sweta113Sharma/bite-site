package com.bitesite.service;

import com.bitesite.dao.OrderDao;
import com.bitesite.dao.OrderRefundDao;
import com.bitesite.dao.PaymentDao;
import com.bitesite.exception.InvalidOrderStateException;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.Order;
import com.bitesite.model.OrderItem;
import com.bitesite.model.OrderRefund;
import com.bitesite.model.OrderStatus;
import com.bitesite.model.Payment;
import com.bitesite.model.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The durable record of "we are about to move money", written in its own transaction.
 *
 * <p>Every method here is {@code REQUIRES_NEW} and that is the entire reason the class
 * exists. A refund is a network call that can succeed while telling you it failed: Razorpay
 * processes it, the HTTP response times out, and we throw. If the claim lived in the
 * caller's transaction it would roll back with everything else, the payment would return to
 * CAPTURED, and the next cancel would refund money that had already left.
 *
 * <p>Committing separately means the claim survives the caller's rollback. The payment is
 * left in REFUND_PENDING and flagged, so the outcome is uncertain and VISIBLE rather than
 * uncertain and forgotten. A human resolves it against Razorpay from the reconciliation
 * screen. That is the conservative direction for money: stuck and flagged beats silently
 * refunded twice.
 *
 * <p>It also stops the caller holding a row lock across the gateway call. The claim commits
 * and releases before the network request begins, so a slow Razorpay no longer blocks every
 * concurrent cancel of that order or pins a connection from the pool for the duration.
 *
 * <p>A separate bean rather than a method on {@link OrderService}, because Spring's
 * transaction proxy is per-bean: {@code REQUIRES_NEW} on a self-invoked method is silently
 * ignored, which would leave this looking correct and doing nothing.
 *
 * <p><b>Callers must not be transactional.</b> {@code REQUIRES_NEW} inside an open
 * transaction needs a SECOND connection while the first is still held, so every concurrent
 * caller consumes two from the pool. With ten in production, ten simultaneous cancels would
 * wait on each other until the connection timeout. The first version of this did exactly
 * that and deadlocked the test pool of four on the spot, which is why cancelOrder,
 * cancelOwnOrder and refundOrder are all deliberately non-transactional.
 */
@Component
@RequiredArgsConstructor
public class RefundLedger {

    private final PaymentDao paymentDao;
    private final OrderDao orderDao;
    private final OrderRefundDao orderRefundDao;
    private final BillingService billingService;

    /** What a committed item-cancellation claim decided, for the caller to act on. */
    public record ItemClaim(
            Order orderBefore,
            List<OrderItem> removed,
            BigDecimal refund,
            /* Null when nothing is owed, e.g. every removed line was covered by a discount. */
            Long refundId,
            Long paymentId,
            String gatewayPaymentId) {}

    /**
     * Claims the payment for refunding and commits that immediately, along with what the
     * refund is for, so that whoever settles it later knows what to do with the order.
     *
     * @param cancellationReason written on the order once the refund is confirmed; null
     *                           leaves the order alone (a refund of a COMPLETED order)
     * @param requestedBy        the actor, for the audit log; null for the system
     * @return true if this caller won the claim and may call the gateway. False means
     *         another cancel already holds it, or the outcome of an earlier attempt is
     *         still unresolved — either way, do not touch the gateway.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long paymentId, String cancellationReason, Long requestedBy) {
        return paymentDao.claimForRefund(paymentId, cancellationReason, requestedBy);
    }

    /**
     * Records that a refund was attempted and its outcome is unknown. Committed separately
     * so it survives the caller's rollback — otherwise the one fact worth keeping from a
     * failed refund would be the one thing thrown away.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUnresolved(Long paymentId, String reason) {
        paymentDao.flagForReconciliation(paymentId, reason);
    }

    /**
     * Takes lines off a paid order and claims the refund for them, as one commit, before
     * any gateway is called.
     *
     * <p>Everything that must be true before money moves happens here or not at all: the
     * lines are marked cancelled, the order's amounts are restated, the refund is recorded
     * as PENDING and its amount reserved against the payment. If any check fails the whole
     * transaction rolls back and nothing has changed.
     *
     * <p>Exclusivity comes from locks, not from reading and hoping. The payment row is
     * locked first, then the order and its lines; nothing else holds both at once, so there
     * is no opposite order to deadlock against. Two staff removing items from one order run
     * one after the other, and the second sees the first's cancelled lines and restated
     * amounts. A full
     * cancellation's {@link #claim} is an UPDATE on the same payment row, so it waits too,
     * and afterwards refunds only {@link Payment#refundableAmount()}.
     *
     * @param lineIds distinct ids of lines on this order
     * @throws InvalidOrderStateException if the order or payment cannot take this, or a
     *         selected line is already gone; nothing is written
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ItemClaim claimItemCancellation(Long orderId, Long tenantId, List<Long> lineIds,
            String lineReason, String refundReason, Long requestedBy) {
        Payment payment = paymentDao.lockByOrderId(orderId, tenantId)
                .orElseThrow(() -> new InvalidOrderStateException("This order has no payment to refund."));
        Order order = orderDao.lockByIdAndTenantId(orderId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.PREPARING) {
            throw new InvalidOrderStateException(
                    "Items can only be removed before an order is ready; this one is "
                            + order.getStatus().name().replace('_', ' ').toLowerCase() + ".");
        }
        if (payment.getStatus() == PaymentStatus.REFUND_PENDING) {
            throw new InvalidOrderStateException("A refund for this order is already in progress.");
        }
        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            throw new InvalidOrderStateException(
                    "This order's payment can't be refunded (it is " + payment.getStatus() + ").");
        }

        Map<Long, OrderItem> lines = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getId, Function.identity()));
        List<OrderItem> removed = lineIds.stream().map(lines::get).toList();
        if (removed.stream().anyMatch(line -> line == null || line.isCancelled())) {
            throw new InvalidOrderStateException(
                    "One of those items is no longer on the order. Reload the queue and try again.");
        }
        long stillOn = order.getItems().stream().filter(line -> !line.isCancelled()).count();
        if (removed.size() >= stillOn) {
            // The caller routes "every item" to a full cancellation before claiming. Landing
            // here means another removal committed in between and changed the order.
            throw new InvalidOrderStateException(
                    "The order changed while you were choosing. Reload the queue and try again.");
        }

        BigDecimal removedSubtotal = removed.stream().map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BillingService.Restatement restated = billingService.withoutLines(order, removedSubtotal);
        if (restated.refund().compareTo(payment.refundableAmount()) > 0) {
            // Unreachable by the arithmetic, and the CHECK on payments would refuse it anyway.
            // Stated so a future change that breaks the arithmetic fails loudly here.
            throw new IllegalStateException("Refund of " + restated.refund() + " exceeds the "
                    + payment.refundableAmount() + " still refundable on payment " + payment.getId());
        }

        Long refundId = null;
        if (restated.refund().signum() > 0) {
            refundId = orderRefundDao.insertPending(OrderRefund.builder()
                    .tenantId(tenantId)
                    .orderId(orderId)
                    .paymentId(payment.getId())
                    .amount(restated.refund())
                    .reason(refundReason)
                    .requestedBy(requestedBy)
                    .build()).getId();
            paymentDao.reservePartialRefund(payment.getId(), restated.refund());
        }

        if (orderDao.cancelLines(orderId, lineIds, lineReason, refundId) != lineIds.size()) {
            // The locks make this unreachable; the conditional UPDATE is the backstop.
            throw new InvalidOrderStateException(
                    "One of those items is no longer on the order. Reload the queue and try again.");
        }
        orderDao.restateAmounts(orderId, tenantId, restated.foodAmount(), restated.discountAmount(),
                restated.commissionAmount(), restated.totalAmount());

        return new ItemClaim(order, removed, restated.refund(), refundId, payment.getId(),
                payment.getRazorpayPaymentId());
    }

    /**
     * Records that the gateway refused a partial refund: the money is still with us. The
     * reservation is given back, so a later full cancellation would include it, and the
     * payment is flagged because the student was told a refund was on its way.
     *
     * @return false if this refund was already recorded as failed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failPartialRefund(OrderRefund refund, String gatewayRefundId, String reason) {
        if (!orderRefundDao.markFailed(refund.getId(), gatewayRefundId)) {
            return false;
        }
        paymentDao.releasePartialRefund(refund.getPaymentId(), refund.getAmount());
        paymentDao.flagForReconciliation(refund.getPaymentId(), reason);
        return true;
    }
}
