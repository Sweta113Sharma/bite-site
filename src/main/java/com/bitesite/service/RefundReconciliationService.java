package com.bitesite.service;

import com.bitesite.dao.OrderDao;
import com.bitesite.dao.PaymentDao;
import com.bitesite.dto.GatewayRefund;
import com.bitesite.model.Order;
import com.bitesite.model.OrderStatus;
import com.bitesite.model.Payment;
import com.bitesite.model.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Settles refunds whose outcome the synchronous call never delivered.
 *
 * <p>A refund is a network call that can succeed while reporting failure. When it does,
 * {@link OrderService} leaves the payment in REFUND_PENDING and stops, because it does not
 * know whether the money moved. This class is how it finds out, by two routes that cover
 * each other:
 *
 * <ul>
 *   <li><b>The webhook.</b> Razorpay sends {@code refund.processed} or {@code refund.failed}
 *       whether or not our HTTP call heard the answer, so most timeouts are settled within
 *       seconds without anybody asking.</li>
 *   <li><b>The sweep.</b> For anything still pending after the webhook should have landed,
 *       ask Razorpay what it holds against the payment and settle from that. If it holds
 *       nothing, the request never arrived, and it is sent again, up to
 *       {@link #MAX_ATTEMPTS} times in total.</li>
 * </ul>
 *
 * <p>Both routes finish through {@link #settle}, and both can run at once. The status
 * transition is a conditional UPDATE, so exactly one of them cancels the order and tells
 * the student. What neither can decide (a partial refund, a refund Razorpay says failed, a
 * request that keeps failing) stays flagged for a person, with the reason written down.
 *
 * <p>Full refunds only, matched on amount. BiteSite never issues a partial refund, so a
 * refund of any other amount was made by someone at Razorpay's dashboard, and is reported
 * rather than acted on.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RefundReconciliationService {

    /** The original attempt plus two automatic retries. After that a human decides;
     * a request that has failed three times, minutes apart, is not a blip. */
    static final int MAX_ATTEMPTS = 3;

    /** Width of payments.reconciliation_reason. */
    private static final int MAX_REASON_LENGTH = 200;

    private final PaymentDao paymentDao;
    private final OrderDao orderDao;
    private final PaymentGateway paymentGateway;
    private final AuditService auditService;
    private final OrderNotifier orderNotifier;

    /** Razorpay's {@code refund.processed}: the money is on its way back. */
    public void onRefundProcessed(String gatewayPaymentId, GatewayRefund refund) {
        Payment payment = find(gatewayPaymentId, "refund.processed " + refund.id());
        if (payment == null) {
            return;
        }
        if (!isFullAmount(payment, refund.amountRupees())) {
            flagAmountMismatch(payment, refund.amountRupees(), refund.status());
            return;
        }
        if (settle(payment, "webhook refund.processed " + refund.id())) {
            return;
        }
        if (paymentDao.transitionStatus(payment.getId(), PaymentStatus.CAPTURED, PaymentStatus.REFUNDED)) {
            refundedOutsideBiteSite(payment, refund);
            return;
        }
        // The normal case: our own call succeeded and recorded REFUNDED before the webhook
        // arrived. Debug, because this happens on every refund that goes well.
        log.debug("Refund {} for payment {} already settled; webhook is a no-op",
                refund.id(), payment.getId());
    }

    /** Razorpay's {@code refund.failed}: the money is still with us. */
    public void onRefundFailed(String gatewayPaymentId, GatewayRefund refund) {
        Payment payment = find(gatewayPaymentId, "refund.failed " + refund.id());
        if (payment == null) {
            return;
        }
        if (!isFullAmount(payment, refund.amountRupees())) {
            flagAmountMismatch(payment, refund.amountRupees(), refund.status());
            return;
        }
        markFailed(payment, "Razorpay reported refund " + refund.id() + " failed");
    }

    /**
     * Asks Razorpay about every refund still pending after {@code olderThanMinutes}, and
     * settles what it can. Returns how many were settled as refunded.
     *
     * <p>The age threshold is what makes this safe to run alongside a live cancel: an HTTP
     * call cannot still be in flight after that long, so a pending row this old has an
     * outcome, and Razorpay knows it even if we do not.
     */
    public int reconcilePending(int olderThanMinutes) {
        List<Payment> pending = paymentDao.findRefundPendingOlderThan(olderThanMinutes);
        int settled = 0;
        for (Payment payment : pending) {
            try {
                if (reconcile(payment, olderThanMinutes)) {
                    settled++;
                }
            } catch (RuntimeException e) {
                // Most likely Razorpay is unreachable. Nothing local has changed for this
                // row, so the next sweep simply asks again.
                log.error("Could not reconcile the refund for payment {}; will retry next sweep",
                        payment.getId(), e);
            }
        }
        return settled;
    }

    private boolean reconcile(Payment payment, int olderThanMinutes) {
        if (payment.getRazorpayPaymentId() == null || payment.getRazorpayPaymentId().isBlank()) {
            // Nothing to ask Razorpay about: this payment has no gateway reference, so no
            // refund can ever have been sent for it and no sweep will ever resolve it.
            // Flag it and stop, rather than retrying something unaskable every five minutes.
            paymentDao.flagForReconciliation(payment.getId(),
                    "Refund pending but this payment has no Razorpay payment id; cannot be reconciled automatically");
            log.warn("Payment {} is REFUND_PENDING with no Razorpay payment id; left to a human",
                    payment.getId());
            return false;
        }
        List<GatewayRefund> refunds = paymentGateway.refundsFor(payment.getRazorpayPaymentId());
        BigDecimal processed = refunds.stream()
                .filter(GatewayRefund::isProcessed)
                .map(GatewayRefund::amountRupees)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (isFullAmount(payment, processed)) {
            return settle(payment, "reconciliation sweep");
        }
        if (processed.signum() > 0) {
            flagAmountMismatch(payment, processed, "processed");
            return false;
        }
        if (refunds.stream().anyMatch(GatewayRefund::isPending)) {
            // Razorpay has it and is working on it. The webhook will say when it is done;
            // if that never arrives, the next sweep sees it as processed and settles it.
            log.info("Refund for payment {} is still processing at Razorpay", payment.getId());
            return false;
        }
        if (refunds.stream().anyMatch(GatewayRefund::isFailed)) {
            markFailed(payment, "Razorpay reports the refund failed");
            return false;
        }

        // Razorpay holds nothing against this payment: the request never arrived. Send it
        // again, if the cap allows. The claim is conditional on the last attempt being old
        // enough, so two sweeps running at once cannot both send.
        if (!paymentDao.claimRefundRetry(payment.getId(), olderThanMinutes, MAX_ATTEMPTS)) {
            paymentDao.flagForReconciliation(payment.getId(), clamp(
                    "Razorpay has no refund for this payment after " + payment.getRefundAttempts()
                            + " attempts; needs a human"));
            log.warn("Refund for payment {} not retried: {} attempts already, giving it to a human",
                    payment.getId(), payment.getRefundAttempts());
            return false;
        }
        int attempt = payment.getRefundAttempts() + 1;
        try {
            paymentGateway.refund(payment.getRazorpayPaymentId(), payment.getAmount());
        } catch (RuntimeException gatewayFailed) {
            paymentDao.flagForReconciliation(payment.getId(), clamp(
                    "Refund attempt " + attempt + " of " + MAX_ATTEMPTS + " failed, outcome unknown: "
                            + gatewayFailed.getMessage()));
            log.error("Refund retry {} of {} for payment {} failed; still REFUND_PENDING",
                    attempt, MAX_ATTEMPTS, payment.getId(), gatewayFailed);
            return false;
        }
        return settle(payment, "reconciliation sweep, attempt " + attempt);
    }

    /**
     * REFUND_PENDING to REFUNDED, then whatever the order was owed: cancellation if the
     * claim asked for one and the order is still live, and a word to the student either
     * way. Returns false if the payment was not pending, which means someone else settled
     * it first and has already done all of this.
     */
    private boolean settle(Payment payment, String via) {
        if (!paymentDao.transitionStatus(payment.getId(), PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED)) {
            return false;
        }
        paymentDao.clearReconciliation(payment.getId());
        auditService.record(payment.getRefundRequestedBy(), payment.getTenantId(), "Payment", payment.getId(),
                "REFUND_SETTLED", PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED);
        log.info("Refund for payment {} settled via {}", payment.getId(), via);

        Optional<Order> found = orderDao.findByIdAndTenantId(payment.getOrderId(), payment.getTenantId());
        if (found.isEmpty()) {
            log.error("Payment {} refunded but its order {} is missing; nothing to cancel",
                    payment.getId(), payment.getOrderId());
            return true;
        }
        Order order = found.get();
        if (payment.getRefundReason() != null && order.getStatus().isLive()) {
            orderDao.cancel(order.getId(), order.getTenantId(), payment.getRefundReason());
            auditService.record(payment.getRefundRequestedBy(), order.getTenantId(), "Order", order.getId(),
                    "STATUS_CANCELLED", order.getStatus(), OrderStatus.CANCELLED);
            orderNotifier.notifyOrderUpdate(order.getUserId(), "Order cancelled",
                    "Your order " + order.getTokenNo() + " was cancelled and refunded: " + payment.getRefundReason());
        } else {
            orderNotifier.notifyOrderUpdate(order.getUserId(), "Refund issued",
                    "Your order " + order.getTokenNo() + " has been refunded. It should reach your account in 5-7 days.");
        }
        return true;
    }

    /**
     * The money did not move. From REFUND_PENDING that means back to CAPTURED, flagged,
     * so the support desk can refund it again once someone has looked at why.
     *
     * <p>If we had already recorded it as REFUNDED (our call got a 200 and the order may be
     * cancelled on the strength of it), the status is left alone and only the flag is
     * raised: flipping a REFUNDED payment back on a webhook that may be a late retry is
     * riskier than a human reading the note.
     */
    private void markFailed(Payment payment, String what) {
        if (paymentDao.transitionStatus(payment.getId(), PaymentStatus.REFUND_PENDING, PaymentStatus.CAPTURED)) {
            paymentDao.flagForReconciliation(payment.getId(), clamp(
                    what + "; the money is still with us and the order is still live"));
            auditService.record(payment.getRefundRequestedBy(), payment.getTenantId(), "Payment", payment.getId(),
                    "REFUND_FAILED", PaymentStatus.REFUND_PENDING, PaymentStatus.CAPTURED);
            log.error("{} for payment {}; returned to CAPTURED and flagged", what, payment.getId());
        } else if (payment.getStatus() == PaymentStatus.REFUNDED) {
            paymentDao.flagForReconciliation(payment.getId(), clamp(
                    what + " after it was recorded as refunded; the student may have neither food nor money"));
            log.error("{} for payment {} which is already recorded as REFUNDED; flagged", what, payment.getId());
        } else {
            log.warn("{} for payment {} in status {}; ignored", what, payment.getId(), payment.getStatus());
        }
    }

    /**
     * A full refund we did not initiate: usually a human refunding a stranded capture from
     * the Razorpay dashboard, which is exactly what the reconciliation flag asked them to
     * do, so the flag comes off. If the order is somehow still live, the flag stays on with
     * the new reason, because a refunded order in the kitchen queue is its own problem.
     */
    private void refundedOutsideBiteSite(Payment payment, GatewayRefund refund) {
        Optional<Order> order = orderDao.findByIdAndTenantId(payment.getOrderId(), payment.getTenantId());
        boolean orderLive = order.map(o -> o.getStatus().isLive()).orElse(false);
        if (orderLive) {
            paymentDao.flagForReconciliation(payment.getId(), clamp(
                    "Refunded at Razorpay outside BiteSite (" + refund.id() + ") while the order was "
                            + order.get().getStatus() + "; the order is still live"));
        } else {
            paymentDao.clearReconciliation(payment.getId());
        }
        auditService.record(null, payment.getTenantId(), "Payment", payment.getId(),
                "REFUNDED_AT_GATEWAY", PaymentStatus.CAPTURED, PaymentStatus.REFUNDED);
        log.warn("Payment {} was refunded at Razorpay outside BiteSite ({}); order {}",
                payment.getId(), refund.id(), orderLive ? "still live, flagged" : "closed, flag cleared");
    }

    private Payment find(String gatewayPaymentId, String what) {
        Optional<Payment> payment = paymentDao.findByRazorpayPaymentId(gatewayPaymentId);
        if (payment.isEmpty()) {
            // Same reasoning as the capture webhook: a retry will never find the row, so
            // the controller answers 200 and this line is the record.
            log.error("Razorpay sent {} for payment {} with no matching payment row; "
                    + "acknowledged, needs manual reconciliation", what, gatewayPaymentId);
        }
        return payment.orElse(null);
    }

    private static boolean isFullAmount(Payment payment, BigDecimal amount) {
        return amount.compareTo(payment.getAmount()) == 0;
    }

    private void flagAmountMismatch(Payment payment, BigDecimal amount, String state) {
        paymentDao.flagForReconciliation(payment.getId(), clamp(
                "Razorpay shows ₹" + amount.toPlainString() + " refunded (" + state + ") against this ₹"
                        + payment.getAmount().toPlainString() + " payment; BiteSite only issues full refunds"));
        log.error("Payment {}: Razorpay shows ₹{} refunded ({}) against a ₹{} payment; flagged",
                payment.getId(), amount, state, payment.getAmount());
    }

    private static String clamp(String reason) {
        return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }
}
