package com.bitesite.controller.student;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.dto.CartLine;
import com.bitesite.model.MenuItem;
import com.bitesite.model.Outlet;
import com.bitesite.model.User;
import com.bitesite.service.Cart;
import com.bitesite.service.CartPersistence;
import com.bitesite.service.MenuService;
import com.bitesite.service.OutletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/student/cart")
@RequiredArgsConstructor
public class CartController {

    private final Cart cart;
    private final MenuService menuService;
    private final CartPersistence cartPersistence;
    private final OutletService outletService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public String view(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        User user = principal.getUser();
        // A cart built before a lecture should still be here after it. Once per session,
        // and only into an empty cart — see CartPersistence.
        cartPersistence.hydrateOnce(user, cart);
        Outlet outlet = cart.getOutletId() != null
                ? outletService.get(cart.getOutletId(), user.getTenantId()) : null;
        // One roll-up for the whole cart rather than one per line.
        Map<Long, Integer> soldToday = outlet == null
                ? Map.of() : menuService.soldToday(user.getTenantId(), outlet.getId());

        List<CartLine> lines = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> entry : cart.getQuantities().entrySet()) {
            MenuItem item = menuService.get(entry.getKey(), user.getTenantId());
            item.setSoldToday(soldToday.getOrDefault(item.getId(), 0));
            BigDecimal lineTotal = item.effectivePrice().multiply(BigDecimal.valueOf(entry.getValue()));
            total = total.add(lineTotal);
            lines.add(new CartLine(item, entry.getValue(), lineTotal));
            // Surfaced here rather than left for checkout to reject: the cart survives in
            // the session across the minutes in which the canteen sells out or switches an
            // item off, and finding that out only after tapping Pay is a bad way to learn it.
            addWarningIfBlocked(warnings, item, entry.getValue());
        }
        if (outlet != null && !outlet.isAcceptingOrders()) {
            warnings.add(outlet.getName() + " has paused new orders for now.");
        }

        model.addAttribute("lines", lines);
        model.addAttribute("total", total);
        model.addAttribute("outlet", outlet);
        model.addAttribute("cartWarnings", warnings);
        model.addAttribute("pageTitle", "Cart");
        return "student/cart";
    }

    private void addWarningIfBlocked(List<String> warnings, MenuItem item, int quantity) {
        if (!item.isAvailable()) {
            warnings.add(item.getName() + " is no longer being served.");
        } else if (item.soldOutToday()) {
            warnings.add(item.getName() + " is sold out for today.");
        } else if (item.remainingToday() != null && quantity > item.remainingToday()) {
            warnings.add("Only " + item.remainingToday() + " left of " + item.getName() + " today.");
        }
    }

    @PostMapping("/add")
    public String add(@AuthenticationPrincipal AppUserPrincipal principal, @RequestParam Long menuItemId,
            @RequestParam(defaultValue = "1") int quantity, @RequestParam Long outletId,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept,
            HttpServletResponse response) throws IOException {
        User user = principal.getUser();
        // menuService.get enforces the tenant boundary — a menuItemId from another tenant 404s here.
        MenuItem item = menuService.getWithTodayCount(menuItemId, user.getTenantId());
        int wanted = Math.max(1, quantity);
        String blocked = blockedReason(user, item, outletId, wanted);
        if (blocked == null) {
            cart.ensureOutlet(outletId);
            cart.add(menuItemId, wanted);
            cartPersistence.persist(user, cart);
        }
        // The menu page adds to cart via fetch() so it can update the badge/sticky bar without a
        // page reload; it asks for JSON explicitly. A plain form post (no-JS fallback) still gets
        // the redirect below.
        if (accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE)) {
            Map<String, Object> body = new HashMap<>();
            body.put("count", cart.getQuantities().values().stream().mapToInt(Integer::intValue).sum());
            body.put("blocked", blocked != null);
            body.put("message", blocked);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), body);
            return null;
        }
        return "redirect:/student/menu?outletId=" + outletId;
    }

    /**
     * Everything that can stop an item going into the cart, in one place, phrased for the
     * student. Null means it can be added.
     *
     * <p>The cap is checked against what would be in the cart afterwards, not just this
     * tap, so five taps on the last two dosas is stopped at the third rather than at
     * checkout. It is still re-checked at checkout, which is the authoritative point — a
     * cart can sit open while the rest of the queue orders the same thing.
     */
    private String blockedReason(User user, MenuItem item, Long outletId, int adding) {
        if (!item.getOutletId().equals(outletId)) {
            return "That item belongs to a different canteen.";
        }
        Outlet outlet = outletService.get(outletId, user.getTenantId());
        if (!outlet.isActive() || !outlet.isAcceptingOrders()) {
            return outlet.getName() + " isn't taking orders right now.";
        }
        if (!item.isAvailable()) {
            return item.getName() + " is not being served right now.";
        }
        Integer remaining = item.remainingToday();
        if (remaining == null) {
            return null;
        }
        if (remaining == 0) {
            return item.getName() + " is sold out for today.";
        }
        int alreadyInCart = cart.getQuantities().getOrDefault(item.getId(), 0);
        return alreadyInCart + adding > remaining
                ? "Only " + remaining + " left of " + item.getName() + " today."
                : null;
    }

    /**
     * Quantity change from either the cart page (form post, redirects) or the menu
     * page's inline stepper (fetch, wants JSON back). Quantity is clamped to the
     * same 0..20 the UI offers, so a hand-rolled post can't park 10,000 samosas in
     * a session; 0 removes the line, matching {@link Cart#setQuantity}.
     */
    @PostMapping("/update")
    public String update(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam Long menuItemId, @RequestParam int quantity,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept,
            HttpServletResponse response) throws IOException {
        int clamped = Math.max(0, Math.min(20, quantity));
        cart.setQuantity(menuItemId, clamped);
        cartPersistence.persist(principal.getUser(), cart);

        if (accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE)) {
            // Re-read prices from the service (never trust a client) and roll up the
            // affected line and the whole cart so the cart page can update totals in
            // place instead of reloading.
            User user = principal.getUser();
            BigDecimal lineTotal = BigDecimal.ZERO;
            BigDecimal total = BigDecimal.ZERO;
            for (Map.Entry<Long, Integer> entry : cart.getQuantities().entrySet()) {
                MenuItem item = menuService.get(entry.getKey(), user.getTenantId());
                BigDecimal lt = item.effectivePrice().multiply(BigDecimal.valueOf(entry.getValue()));
                total = total.add(lt);
                if (entry.getKey().equals(menuItemId)) {
                    lineTotal = lt;
                }
            }
            int count = cart.getQuantities().values().stream().mapToInt(Integer::intValue).sum();
            Map<String, Object> body = new HashMap<>();
            body.put("count", count);
            body.put("quantity", clamped);
            body.put("lineTotal", lineTotal);
            body.put("total", total);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), body);
            return null;
        }
        return "redirect:/student/cart";
    }

    @PostMapping("/remove")
    public String remove(@AuthenticationPrincipal AppUserPrincipal principal, @RequestParam Long menuItemId,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept,
            HttpServletResponse response) throws IOException {
        cart.remove(menuItemId);
        cartPersistence.persist(principal.getUser(), cart);

        if (accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE)) {
            // The undo toast on the menu page and the remove buttons on the cart page both
            // post here via fetch() asking for JSON so the badge can update without a reload.
            int count = cart.getQuantities().values().stream().mapToInt(Integer::intValue).sum();
            Map<String, Object> body = new HashMap<>();
            body.put("count", count);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), body);
            return null;
        }
        return "redirect:/student/cart";
    }
}
