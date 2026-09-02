package com.bitesite.controller.student;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.Order;
import com.bitesite.model.OrderItem;
import com.bitesite.model.MenuItem;
import com.bitesite.model.User;
import com.bitesite.service.OrderService;
import com.bitesite.service.MenuService;
import com.bitesite.service.Cart;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/student/orders")
@RequiredArgsConstructor
public class OrderHistoryController {

    private final OrderService orderService;
    private final MenuService menuService;
    private final Cart cart;

    @GetMapping
    public String history(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        User user = principal.getUser();
        // The screen shows two groups. Active ones arrive via GlobalModelAttributes
        // (they are needed on every customer page for the strip); this supplies only the
        // finished ones, so nothing is listed twice.
        List<Order> past = orderService.historyForUser(user.getId(), user.getTenantId()).stream()
                .filter(o -> o.getStatus().isTerminal())
                .toList();
        model.addAttribute("orders", past);
        model.addAttribute("pastOrders", past);
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

    /**
     * Puts a past order's items back in the cart.
     *
     * <p>Rebuilt from the current menu rather than copied from the old order: prices move,
     * items get withdrawn, and daily caps fill up. Anything no longer orderable is dropped
     * and reported, so the student is told what changed instead of discovering it at
     * checkout. Nothing about the old order's pricing is carried forward — the cart holds
     * ids and quantities only, and checkout re-prices from the database as always.
     */
    @PostMapping("/{orderId}/reorder")
    public String reorder(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long orderId,
            RedirectAttributes redirectAttributes) {
        User user = principal.getUser();
        Order order = orderService.getForUser(orderId, user.getId(), user.getTenantId());

        // Switching outlet empties the cart, so this has to happen before anything is added.
        cart.ensureOutlet(order.getOutletId());

        List<String> unavailable = new ArrayList<>();
        int added = 0;
        for (OrderItem line : order.getItems()) {
            MenuItem item;
            try {
                item = menuService.getWithTodayCount(line.getMenuItemId(), user.getTenantId());
            } catch (ResourceNotFoundException e) {
                // The item was deleted from the menu outright.
                unavailable.add(line.getItemNameSnapshot());
                continue;
            }
            if (!item.orderable()) {
                unavailable.add(item.getName());
                continue;
            }
            Integer remaining = item.remainingToday();
            int wanted = remaining == null ? line.getQuantity() : Math.min(line.getQuantity(), remaining);
            cart.add(item.getId(), wanted);
            added++;
        }

        if (added == 0) {
            redirectAttributes.addFlashAttribute("cartError",
                    "Nothing from that order is available right now.");
        } else if (!unavailable.isEmpty()) {
            redirectAttributes.addFlashAttribute("cartError",
                    "Added what we could. Not available today: " + String.join(", ", unavailable) + ".");
        }
        return "redirect:/student/cart";
    }
}
