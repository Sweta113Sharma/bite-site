package com.bitesite.exception;

/**
 * The gateway's own rules refused a refund before any request was made, so no money moved.
 *
 * <p>Different in kind from a plain {@link PaymentGatewayException}, which for a refund means
 * the outcome is unknown: the call may have reached the gateway and succeeded. Callers can
 * record this one as a failure outright instead of leaving it for reconciliation to chase.
 */
public class RefundNotSentException extends PaymentGatewayException {
    public RefundNotSentException(String message) {
        super(message);
    }
}
