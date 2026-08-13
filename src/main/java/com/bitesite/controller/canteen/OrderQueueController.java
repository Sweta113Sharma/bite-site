package com.bitesite.controller.canteen;

import com.bitesite.config.AppUserPrincipal;
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

@Controller
@RequestMapping("/canteen/queue")
@RequiredArgsConstructor
public class OrderQueueController {

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
        User user = principal.getUser();
        orderService.advanceStatus(orderId, user.getTenantId(), newStatus, user.getId());
        return "redirect:/canteen/queue";
    }
}
