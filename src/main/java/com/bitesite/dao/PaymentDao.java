package com.bitesite.dao;

import com.bitesite.model.Payment;
import com.bitesite.model.PaymentStatus;

import java.util.List;
import java.util.Optional;

public interface PaymentDao {
    Payment save(Payment payment);

    Optional<Payment> findByOrderId(Long orderId, Long tenantId);

    Optional<Payment> findByRazorpayOrderId(String razorpayOrderId);

    /** Webhook lookup: refund events carry the payment id, never our order. */
    Optional<Payment> findByRazorpayPaymentId(String razorpayPaymentId);

    /**
     * Support-desk lookup by whichever Razorpay reference the student actually has:
     * the order id (order_...) from checkout, or the payment id (pay_...) off their
     * bank statement. Not tenant-scoped — gate on the admin role.
     */
    Optional<Payment> findByAnyGatewayReference(String reference);

    /**
     * Recent payments across every tenant, newest first, optionally narrowed by status.
     * Admin reconciliation only — gate on the admin role, as with the finder above.
     */
    List<Payment> findRecentAcrossTenants(PaymentStatus status, int limit, int offset);

    void markVerified(Long id, String razorpayPaymentId, String razorpaySignature, PaymentStatus status);

    /**
     * Moves CAPTURED to REFUND_PENDING, atomically. Returns true only for the caller that
     * won; everyone else gets false and must not touch the gateway.
     *
     * <p>The claim is a conditional UPDATE, so the check and the act are one statement and
     * the database decides the winner. Reading the status and then refunding is
     * check-then-act with a network call as the act, and it double-refunded: eight
     * simultaneous cancels of one order asked Razorpay to refund it four times.
     *
     * <p>No constraint can catch this after the fact, unlike the order token or the pickup
     * code. The money leaves at the gateway before anything local changes, so exclusivity
     * has to be established BEFORE the call rather than detected after it.
     *
     * <p>The claim also records the intent, because whoever settles the refund later (the
     * webhook, the sweep) has no other way to know what the order is owed.
     *
     * @param cancellationReason written on the order once the refund is confirmed; null
     *                           leaves the order alone
     * @param requestedBy        the actor, for the audit log; null for the system
     */
    boolean claimForRefund(Long id, String cancellationReason, Long requestedBy);

    /**
     * Claims one more attempt at a refund still in REFUND_PENDING, atomically. Wins only if
     * the last attempt is at least {@code olderThanMinutes} old (so nothing is still in
     * flight) and fewer than {@code maxAttempts} have been made. Winning bumps both, so a
     * second sweep, or a second instance, running at the same moment matches nothing.
     */
    boolean claimRefundRetry(Long id, int olderThanMinutes, int maxAttempts);

    /**
     * Moves the status only if it is currently {@code from}. Returns true for exactly one
     * of any number of concurrent callers, which is what lets the webhook and the sweep
     * both try to settle the same refund without both cancelling the order.
     */
    boolean transitionStatus(Long id, PaymentStatus from, PaymentStatus to);

    /** Refunds whose last gateway attempt is old enough that an HTTP call cannot still be
     * in flight, and whose outcome is therefore knowable by asking. */
    List<Payment> findRefundPendingOlderThan(int minutes);

    void updateStatus(Long id, PaymentStatus status);

    /** Marks a captured payment as needing a human — see V22. */
    void flagForReconciliation(Long id, String reason);

    void clearReconciliation(Long id);

    List<Payment> findNeedingReconciliation(int limit, int offset);

    /** For the admin overview. Cheap: covered by idx_payments_reconciliation. */
    long countNeedingReconciliation();
}
