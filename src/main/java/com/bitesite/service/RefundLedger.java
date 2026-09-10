package com.bitesite.service;

import com.bitesite.dao.PaymentDao;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Claims the payment for refunding and commits that immediately.
     *
     * @return true if this caller won the claim and may call the gateway. False means
     *         another cancel already holds it, or the outcome of an earlier attempt is
     *         still unresolved — either way, do not touch the gateway.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long paymentId) {
        return paymentDao.claimForRefund(paymentId);
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
}
