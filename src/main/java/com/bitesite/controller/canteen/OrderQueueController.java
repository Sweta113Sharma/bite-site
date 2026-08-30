package com.bitesite.controller.canteen;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.exception.InvalidOrderStateException;
import com.bitesite.model.OrderStatus;
import com.bitesite.model.User;
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

    @GetMapping
    public String queue(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        User user = principal.getUser();
        model.addAttribute("orders", orderService.kitchenQueue(user.getTenantId(), user.getOutletId()));
        model.addAttribute("outlet", outletService.get(user.getOutletId(), user.getTenantId()));
        model.addAttribute("pageTitle", "Order queue");
        return "canteen/queue";
    }

    @PostMapping("/{orderId}/status")
    public String updateStatus(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long orderId,
            @RequestParam OrderStatus newStatus) {
        if (!STAFF_ADVANCEABLE.contains(newStatus)) {
            throw new InvalidOrderStateException("That status change isn't available from here.");
        }
        User user = principal.getUser();
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
        User user = principal.getUser();
        orderService.cancelOrder(orderId, user.getTenantId(), user.getId(), reason);
        return "redirect:/canteen/queue";
    }

    /**
     * Hands an order over, against the code on the student's screen. A mismatch is
     * reported back to the counter rather than thrown as an error page, because the
     * likeliest cause is a mistyped digit and staff need to just try again.
     */
    @PostMapping("/{orderId}/collect")
    public String completePickup(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long orderId,
            @RequestParam(required = false) String pickupCode, RedirectAttributes redirectAttributes) {
        User user = principal.getUser();
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
        User user = principal.getUser();
        outletService.setAcceptingOrders(user.getOutletId(), user.getTenantId(), accepting, user.getId());
        redirectAttributes.addFlashAttribute("queueNotice", accepting
                ? "Taking new orders again."
                : "New orders paused. Everything already in the queue is unaffected.");
        return "redirect:/canteen/queue";
    }
}
