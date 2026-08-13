package com.bitesite.controller.student;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.dto.CartLine;
import com.bitesite.model.MenuItem;
import com.bitesite.model.User;
import com.bitesite.service.Cart;
import com.bitesite.service.MenuService;
import com.bitesite.service.OutletService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/student/cart")
@RequiredArgsConstructor
public class CartController {

    private final Cart cart;
    private final MenuService menuService;
    private final OutletService outletService;

    @GetMapping
    public String view(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        User user = principal.getUser();
        List<CartLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> entry : cart.getQuantities().entrySet()) {
            MenuItem item = menuService.get(entry.getKey(), user.getTenantId());
            BigDecimal lineTotal = item.effectivePrice().multiply(BigDecimal.valueOf(entry.getValue()));
            total = total.add(lineTotal);
            lines.add(new CartLine(item, entry.getValue(), lineTotal));
        }
        model.addAttribute("lines", lines);
        model.addAttribute("total", total);
        model.addAttribute("outlet",
                cart.getOutletId() != null ? outletService.get(cart.getOutletId(), user.getTenantId()) : null);
        model.addAttribute("pageTitle", "Cart");
        return "student/cart";
    }

    @PostMapping("/add")
    public String add(@AuthenticationPrincipal AppUserPrincipal principal, @RequestParam Long menuItemId,
            @RequestParam(defaultValue = "1") int quantity, @RequestParam Long outletId) {
        User user = principal.getUser();
        // menuService.get enforces the tenant boundary — a menuItemId from another tenant 404s here.
        MenuItem item = menuService.get(menuItemId, user.getTenantId());
        if (item.isAvailable() && item.getOutletId().equals(outletId)) {
            cart.ensureOutlet(outletId);
            cart.add(menuItemId, Math.max(1, quantity));
        }
        return "redirect:/student/menu?outletId=" + outletId;
    }

    @PostMapping("/update")
    public String update(@RequestParam Long menuItemId, @RequestParam int quantity) {
        cart.setQuantity(menuItemId, quantity);
        return "redirect:/student/cart";
    }

    @PostMapping("/remove")
    public String remove(@RequestParam Long menuItemId) {
        cart.remove(menuItemId);
        return "redirect:/student/cart";
    }
}
