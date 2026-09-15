package com.bitesite.service;

import com.bitesite.dao.OrderRefundDao;
import com.bitesite.dto.GatewayRefund;
import com.bitesite.exception.InvalidOrderStateException;
import com.bitesite.exception.RefundNotSentException;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.ItemCancelReason;
import com.bitesite.model.Order;
import com.bitesite.model.OrderItem;
import com.bitesite.model.OrderRefund;
import com.bitesite.model.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Takes items the kitchen cannot make off a paid order, refunds the student for them, and
 * lets the rest of the order carry on.
 *
 * <p>Before this, one missing item left two bad choices: cancel and refund the whole order,
 * or cook four of five and leave the student to chase the fifth through Support.
 *
 * <p>The order of operations is the whole design, and it matches cancelOrder's:
 * <ol>
 *   <li>{@link RefundLedger#claimItemCancellation} commits the lines, the restated amounts
 *       and a PENDING refund together, under locks, before any money moves.</li>
 *   <li>"Out of stock" takes the items off sale for the rest of the day.</li>
 *   <li>Only then is the gateway asked. If that call fails, the outcome is unknown rather
 *       than negative, so the refund stays PENDING and the payment is flagged; Razorpay's
 *       webhook or the reconciliation sweep settles it (RefundReconciliationService).</li>
 * </ol>
 *
 * <p>Taking off every remaining item is a full cancellation, not a partial one, and goes
 * through {@link OrderService#cancelUnmakeable} so the platform fee and tip come back too.
 */
/* Deliberately NOT @Transactional, for the reason cancelOrder is not: the claim has to
   commit before the network call, and holding a transaction across the call would pin a
   connection per concurrent removal. */
@Service
@Slf4j
@RequiredArgsConstructor
public class ItemCancellationService {

    /** Width of order_refunds.reason and payments.reconciliation_reason. */
    private static final int MAX_REASON_LENGTH = 200;

    private final OrderService orderService;
    private final RefundLedger refundLedger;
    private final OrderRefundDao orderRefundDao;
    private final PaymentGateway paymentGateway;
    private final MenuService menuService;
    private final AuditService auditService;
    private final OrderNotifier orderNotifier;

    /**
     * What happened, for the message staff see.
     *
     * @param wholeOrderCancelled every remaining item was selected, so the order was
     *                            cancelled and refunded in full instead
     * @param refund              money owed back for the removed items; zero when a
     *                            discount covered them. Meaningless for a whole cancellation.
     * @param refundConfirmed     false when the gateway call did not report success; the
     *                            refund is then pending and flagged, not lost
     * @param refundNotSent       the gateway refused the refund before sending it (under
     *                            ₹1), so it is recorded as failed and flagged for an admin
     */
    public record Result(
            String tokenNo,
            List<String> removedNames,
            boolean wholeOrderCancelled,
            BigDecimal refund,
            boolean refundConfirmed,
            boolean refundNotSent,
            boolean markedOutOfStock) {}

    /** How a partial refund's gateway call ended. NOT_CHARGED: a no-charge order, where
     * there was no money to send back and the gateway was never asked. */
    private enum RefundOutcome { SENT, UNKNOWN, NOT_SENT, NOT_CHARGED }

    /**
     * @param outletId the staff member's own outlet. Tenant scoping alone would let a
     *                 manager at one canteen reach the college's other canteen's orders.
     */
    public Result cancelItems(Long orderId, Long tenantId, Long outletId, List<Long> lineIds,
            ItemCancelReason reason, Long actorUserId) {
        List<Long> selected = lineIds == null ? List.of() : lineIds.stream().distinct().toList();
        if (selected.isEmpty()) {
            throw new InvalidOrderStateException("Tick the items that can't be made.");
        }
        if (reason == null) {
            throw new InvalidOrderStateException("Choose why the items are being removed.");
        }

        Order order = orderService.getForTenant(orderId, tenantId);
        if (!order.getOutletId().equals(outletId)) {
            throw new ResourceNotFoundException("Order not found");
        }
        if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.PREPARING) {
            throw new InvalidOrderStateException(
                    "Items can only be removed before an order is ready.");
        }

        Set<Long> onOrder = order.getItems().stream()
                .filter(line -> !line.isCancelled())
                .map(OrderItem::getId)
                .collect(Collectors.toSet());
        if (!onOrder.containsAll(selected)) {
            throw new InvalidOrderStateException(
                    "One of those items is no longer on the order. Reload the queue and try again.");
        }
        List<OrderItem> removedLines = order.getItems().stream()
                .filter(line -> selected.contains(line.getId()))
                .toList();
        List<String> names = removedLines.stream().map(OrderItem::getItemNameSnapshot).toList();

        if (selected.size() == onOrder.size()) {
            // Nothing would be left to make: that is the order, not some of its items.
            String why = clamp(reason.label() + ": " + String.join(", ", names));
            orderService.cancelUnmakeable(orderId, tenantId, actorUserId, why);
            boolean marked = markOutOfStockIfAsked(reason, removedLines, outletId, tenantId, actorUserId);
            return new Result(order.getTokenNo(), names, true, BigDecimal.ZERO, true, false, marked);
        }

        RefundLedger.ItemClaim claim = refundLedger.claimItemCancellation(orderId, tenantId, selected,
                reason.label(), clamp("Removed from " + order.getTokenNo() + " (" + reason.label() + "): "
                        + String.join(", ", names)),
                actorUserId);

        // After the claim commits, before the gateway: stock is about the kitchen, not the
        // money, and a refund that times out must not leave the dish on sale.
        boolean marked = markOutOfStockIfAsked(reason, claim.removed(), outletId, tenantId, actorUserId);
        auditService.record(actorUserId, tenantId, "Order", orderId, "ITEMS_REMOVED",
                claim.orderBefore().getTotalAmount(), names + " (" + reason.name() + "), refund " + claim.refund());

        RefundOutcome outcome = RefundOutcome.SENT;
        if (claim.refundId() != null && order.isNoCharge()) {
            // The review account's order took no money and its payment id is invented, so
            // Razorpay cannot be asked. The refund row is settled with no gateway id.
            orderRefundDao.markRefunded(claim.refundId(), null);
            outcome = RefundOutcome.NOT_CHARGED;
        } else if (claim.refundId() != null) {
            outcome = sendRefund(claim, order.getTokenNo());
        }

        orderNotifier.notifyOrderUpdate(order.getUserId(),
                names.size() == 1 ? "An item in your order is unavailable" : "Some items in your order are unavailable",
                studentMessage(order.getTokenNo(), names, reason, claim.refund(), outcome));
        log.info("Removed {} line(s) from order {} ({}), refund {} {}", names.size(), orderId, reason,
                claim.refund(), outcome);
        return new Result(order.getTokenNo(), names, false, claim.refund(),
                outcome == RefundOutcome.SENT || outcome == RefundOutcome.NOT_CHARGED,
                outcome == RefundOutcome.NOT_SENT, marked);
    }

    private RefundOutcome sendRefund(RefundLedger.ItemClaim claim, String tokenNo) {
        try {
            GatewayRefund issued = paymentGateway.refundPart(claim.gatewayPaymentId(), claim.refund());
            orderRefundDao.markRefunded(claim.refundId(), issued == null ? null : issued.id());
            return RefundOutcome.SENT;
        } catch (RefundNotSentException refused) {
            // Refused before any request left, so no money moved: a known failure, not an
            // unknown outcome. Left PENDING, every reconciliation sweep would look for it at
            // Razorpay, find nothing, and flag it again. Failing it gives the reservation
            // back, so a later full cancellation refunds this amount with the rest.
            refundLedger.failPartialRefund(OrderRefund.builder()
                    .id(claim.refundId())
                    .paymentId(claim.paymentId())
                    .amount(claim.refund())
                    .build(), null, clamp("Partial refund of ₹" + claim.refund().toPlainString()
                    + " for items removed from " + tokenNo + " not sent: " + refused.getMessage()));
            log.warn("Partial refund {} of {} for payment {} not sent: {}", claim.refundId(), claim.refund(),
                    claim.paymentId(), refused.getMessage());
            return RefundOutcome.NOT_SENT;
        } catch (RuntimeException gatewayFailed) {
            refundLedger.recordUnresolved(claim.paymentId(), clamp("Partial refund of ₹"
                    + claim.refund().toPlainString() + " for items removed from " + tokenNo
                    + " attempted, outcome unknown: " + gatewayFailed.getMessage()));
            log.error("Partial refund {} outcome unknown for payment {}; left PENDING and flagged",
                    claim.refundId(), claim.paymentId(), gatewayFailed);
            return RefundOutcome.UNKNOWN;
        }
    }

    private boolean markOutOfStockIfAsked(ItemCancelReason reason, List<OrderItem> lines, Long outletId,
            Long tenantId, Long actorUserId) {
        if (!reason.marksOutOfStock()) {
            return false;
        }
        List<Long> menuItemIds = lines.stream().map(OrderItem::getMenuItemId).toList();
        return menuService.markOutOfStockToday(menuItemIds, outletId, tenantId, actorUserId) > 0;
    }

    private static String studentMessage(String tokenNo, List<String> names, ItemCancelReason reason,
            BigDecimal refund, RefundOutcome outcome) {
        String items = String.join(", ", names);
        String money;
        if (refund.signum() <= 0 || outcome == RefundOutcome.NOT_CHARGED) {
            money = "";
        } else if (outcome == RefundOutcome.NOT_SENT) {
            // Never promise money that was not sent.
            money = " ₹" + refund.toPlainString() + " is below the smallest amount that can be refunded "
                    + "automatically, so BiteSite support has been told.";
        } else {
            money = " ₹" + refund.toPlainString() + " is on its way back to how you paid.";
        }
        return items + " from order " + tokenNo + (names.size() == 1 ? " was" : " were")
                + " removed (" + reason.label().toLowerCase() + ")." + money
                + " The rest of your order is still being made.";
    }

    private static String clamp(String text) {
        return text.length() <= MAX_REASON_LENGTH ? text : text.substring(0, MAX_REASON_LENGTH);
    }
}
