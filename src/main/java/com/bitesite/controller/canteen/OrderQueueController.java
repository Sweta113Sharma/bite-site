package com.bitesite.controller.canteen;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.PortalGuard;
import com.bitesite.exception.InvalidOrderStateException;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.ItemCancelReason;
import com.bitesite.model.OrderStatus;
import com.bitesite.model.StaffScope;
import com.bitesite.model.User;
import com.bitesite.service.ItemCancellationService;
import com.bitesite.service.OrderService;
import com.bitesite.service.OutletService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Controller
@RequestMapping("/canteen/queue")
@RequiredArgsConstructor
public class OrderQueueController {

    // The only transitions the kitchen UI actually offers — PAID and CANCELLED are
    // deliberately excluded here even though the state machine allows them: marking PAID
    // must only ever happen through a verified payment (see OrderService.confirmPayment),
    // and cancelling needs the refund handling in cancel() below, not a bare status flip.
    // COMPLETED is no longer here: handing food over is authenticated by the student's
    // pickup code and goes through completePickup() below, not a bare status flip.
    private static final Set<OrderStatus> STAFF_ADVANCEABLE =
            EnumSet.of(OrderStatus.PREPARING, OrderStatus.READY_FOR_PICKUP);

    private final OrderService orderService;
    private final OutletService outletService;
    private final ItemCancellationService itemCancellationService;

    /**
     * Every action below takes an order id from the request, and tenant scoping alone would
     * let staff at one canteen act on the college's other canteen's orders by changing it:
     * start them, hand them over, or cancel and refund them. The order detail page already
     * refused that (OutletAdminController.orderDetail); these did not. Answered as not
     * found, the same as an order that does not exist, so ids cannot be probed.
     */
    private void requireOwnOutlet(Long orderId, User user) {
        if (!orderService.getForTenant(orderId, user.getTenantId()).getOutletId().equals(user.getOutletId())) {
            throw new ResourceNotFoundException("Order not found");
        }
    }

    @GetMapping
    public String queue(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_OPS);
        User user = principal.getUser();
        model.addAttribute("orders", orderService.kitchenQueue(user.getTenantId(), user.getOutletId()));
        model.addAttribute("outlet", outletService.get(user.getOutletId(), user.getTenantId()));
        model.addAttribute("pageTitle", "Order queue");
        return "canteen/queue";
    }

    @PostMapping("/{orderId}/status")
    public String updateStatus(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long orderId,
            @RequestParam OrderStatus newStatus) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_OPS);
        if (!STAFF_ADVANCEABLE.contains(newStatus)) {
            throw new InvalidOrderStateException("That status change isn't available from here.");
        }
        User user = principal.getUser();
        requireOwnOutlet(orderId, user);
        orderService.advanceStatus(orderId, user.getTenantId(), newStatus, user.getId());
        return "redirect:/canteen/queue";
    }

    /**
     * Cancels one order and refunds it. The reason is passed straight through to the
     * student's order page, so it is worth asking for even though it is optional — "the
     * paneer ran out" answers the question a bare CANCELLED badge only raises.
     */
    @PostMapping("/{orderId}/cancel")
    public String cancel(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long orderId,
            @RequestParam(required = false) String reason) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_OPS);
        User user = principal.getUser();
        requireOwnOutlet(orderId, user);
        orderService.cancelOrder(orderId, user.getTenantId(), user.getId(), reason);
        return "redirect:/canteen/queue";
    }

    /**
     * Takes the ticked items off an order, refunds the student for them, and leaves the
     * rest to be made. "Out of stock" also takes those items off sale until tomorrow.
     *
     * <p>Refusals come back to the queue as a message rather than an error page: the likely
     * cause is another member of staff having changed the order a moment earlier, and the
     * fix is to look again.
     */
    @PostMapping("/{orderId}/items/cancel")
    public String cancelItems(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long orderId,
            @RequestParam(name = "lineId", required = false) List<Long> lineIds,
            @RequestParam(required = false) ItemCancelReason reason, RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_OPS);
        User user = principal.getUser();
        try {
            ItemCancellationService.Result result = itemCancellationService.cancelItems(orderId,
                    user.getTenantId(), user.getOutletId(), lineIds, reason, user.getId());
            redirectAttributes.addFlashAttribute("queueNotice", noticeFor(result));
            if (result.refundNotSent()) {
                redirectAttributes.addFlashAttribute("queueError", "₹" + result.refund().toPlainString()
                        + " is under Razorpay's ₹1 minimum, so it wasn't refunded. It is flagged for an admin.");
            } else if (!result.refundConfirmed()) {
                redirectAttributes.addFlashAttribute("queueError", "Razorpay didn't confirm the refund of ₹"
                        + result.refund().toPlainString() + ". It is recorded and flagged, and will be checked "
                        + "automatically. Don't refund it again by hand.");
            }
        } catch (InvalidOrderStateException e) {
            redirectAttributes.addFlashAttribute("queueError", e.getMessage());
        }
        return "redirect:/canteen/queue";
    }

    private static String noticeFor(ItemCancellationService.Result result) {
        String items = String.join(", ", result.removedNames());
        StringBuilder notice = new StringBuilder();
        if (result.wholeOrderCancelled()) {
            notice.append("Nothing was left on ").append(result.tokenNo())
                    .append(", so the whole order was cancelled and refunded in full.");
        } else {
            notice.append("Removed ").append(items).append(" from ").append(result.tokenNo());
            if (result.refund().signum() <= 0) {
                notice.append(". A discount covered them, so there was nothing to refund.");
            } else if (result.refundNotSent()) {
                notice.append(".");
            } else {
                notice.append(" and refunded ₹").append(result.refund().toPlainString()).append(".");
            }
        }
        if (result.markedOutOfStock()) {
            notice.append(" Marked out of stock until tomorrow: ").append(items).append(".");
        }
        return notice.toString();
    }

    /**
     * Hands an order over, against the code on the student's screen. A mismatch is
     * reported back to the counter rather than thrown as an error page, because the
     * likeliest cause is a mistyped digit and staff need to just try again.
     */
    @PostMapping("/{orderId}/collect")
    public String completePickup(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long orderId,
            @RequestParam(required = false) String pickupCode, RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_OPS);
        User user = principal.getUser();
        requireOwnOutlet(orderId, user);
        try {
            orderService.completeWithPickupCode(orderId, user.getTenantId(), pickupCode, user.getId());
        } catch (InvalidOrderStateException e) {
            redirectAttributes.addFlashAttribute("queueError", e.getMessage());
        }
        return "redirect:/canteen/queue";
    }

    /**
     * Pause or resume new orders for this outlet. Staff-facing rather than admin-facing on
     * purpose: the person who knows the kitchen is 20 orders deep is standing in it.
     */
    @PostMapping("/accepting")
    public String setAccepting(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam boolean accepting, RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_OPS);
        User user = principal.getUser();
        outletService.setAcceptingOrders(user.getOutletId(), user.getTenantId(), accepting, user.getId());
        redirectAttributes.addFlashAttribute("queueNotice", accepting
                ? "Taking new orders again."
                : "New orders paused. Everything already in the queue is unaffected.");
        return "redirect:/canteen/queue";
    }
}
