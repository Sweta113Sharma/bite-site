package com.bitesite.dao;

import com.bitesite.model.Payment;
import com.bitesite.model.PaymentStatus;

import java.util.List;
import java.util.Optional;

public interface PaymentDao {
    Payment save(Payment payment);

    Optional<Payment> findByOrderId(Long orderId, Long tenantId);

    Optional<Payment> findByRazorpayOrderId(String razorpayOrderId);

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
     */
    boolean claimForRefund(Long id);

    void updateStatus(Long id, PaymentStatus status);

    /** Marks a captured payment as needing a human — see V22. */
    void flagForReconciliation(Long id, String reason);

    void clearReconciliation(Long id);

    List<Payment> findNeedingReconciliation(int limit, int offset);

    /** For the admin overview. Cheap: covered by idx_payments_reconciliation. */
    long countNeedingReconciliation();
}
