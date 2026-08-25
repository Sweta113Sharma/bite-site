package com.bitesite.controller.student;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.model.Order;
import com.bitesite.model.User;
import com.bitesite.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/student/orders")
@RequiredArgsConstructor
public class OrderHistoryController {

    private final OrderService orderService;

    @GetMapping
    public String history(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        User user = principal.getUser();
        model.addAttribute("orders", orderService.historyForUser(user.getId(), user.getTenantId()));
        model.addAttribute("pageTitle", "My orders");
        return "student/orders";
    }

    @GetMapping("/{orderId}")
    public String detail(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long orderId, Model model) {
        User user = principal.getUser();
        Order order = orderService.getForUser(orderId, user.getId(), user.getTenantId());
        model.addAttribute("order", order);
        // Whether the money came back is the first thing a student wants to know about a
        // cancelled order, and it lives on the payment, not the order. Absent for an order
        // that never reached the gateway, so the page has to cope with null either way.
        model.addAttribute("payment", orderService.findPaymentForOrder(orderId, user.getTenantId()).orElse(null));
        model.addAttribute("pageTitle", "Order " + order.getTokenNo());
        return "student/order-detail";
    }
}
