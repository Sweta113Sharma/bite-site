package com.bitesite.controller.canteen;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.exception.InvalidOrderStateException;
import com.bitesite.model.OrderStatus;
import com.bitesite.model.User;
import com.bitesite.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

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
    private static final Set<OrderStatus> STAFF_ADVANCEABLE =
            EnumSet.of(OrderStatus.PREPARING, OrderStatus.READY_FOR_PICKUP, OrderStatus.COMPLETED);

    private final OrderService orderService;

    @GetMapping
    public String queue(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        User user = principal.getUser();
        model.addAttribute("orders", orderService.kitchenQueue(user.getTenantId(), user.getOutletId()));
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

    @PostMapping("/{orderId}/cancel")
    public String cancel(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long orderId) {
        User user = principal.getUser();
        orderService.cancelOrder(orderId, user.getTenantId(), user.getId());
        return "redirect:/canteen/queue";
    }
}
