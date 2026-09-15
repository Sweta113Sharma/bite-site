package com.bitesite.dao;

import com.bitesite.model.OrderRefund;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Partial refunds: money paid back for lines taken off a paid order. */
public interface OrderRefundDao {

    OrderRefund insertPending(OrderRefund refund);

    /**
     * Records a refund Razorpay has already processed that BiteSite did not send (made in
     * the Razorpay dashboard), as REFUNDED with its gateway id.
     *
     * @return false if a row with that gateway refund id already exists, which is a
     *         redelivered webhook
     */
    boolean insertSettledFromGateway(OrderRefund refund);

    List<OrderRefund> findByOrderId(Long orderId, Long tenantId);

    Optional<OrderRefund> findByGatewayRefundId(String gatewayRefundId);

    /** Oldest PENDING partial refund of exactly this amount on this payment with no gateway
     * id yet: the one a refund.processed webhook belongs to when our call timed out. */
    Optional<OrderRefund> findUnmatchedPending(Long paymentId, BigDecimal amount);

    List<OrderRefund> findPendingOlderThan(int minutes);

    /** Gateway ids BiteSite already accounts for as partial refunds of this payment. */
    List<String> gatewayIdsForPayment(Long paymentId);

    /** PENDING to REFUNDED. False if someone else settled it first. */
    boolean markRefunded(Long id, String gatewayRefundId);

    /** PENDING or REFUNDED to FAILED. False if it was already FAILED. */
    boolean markFailed(Long id, String gatewayRefundId);

    int countPending(Long paymentId);
}
