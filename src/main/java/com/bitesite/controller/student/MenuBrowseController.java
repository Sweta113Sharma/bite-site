package com.bitesite.controller.student;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.BusinessClock;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.MenuItem;
import com.bitesite.model.Outlet;
import com.bitesite.model.User;
import com.bitesite.service.Cart;
import com.bitesite.service.MenuService;
import com.bitesite.service.OrderService;
import com.bitesite.service.OutletService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Student ordering flow: log in → pick a canteen (only when the college has more than
 * one — {@link #selectOutlet}) → browse that canteen's menu ({@link #browse}). The
 * chosen outlet is remembered on the session {@link Cart} for the rest of the session,
 * so navigating back to "Menu" doesn't re-ask; picking a *different* outlet from the
 * picker or the in-page dropdown clears the cart (see {@link Cart#ensureOutlet}) rather
 * than mixing items from two different canteens into one order.
 */
@Controller
@RequestMapping("/student/menu")
@RequiredArgsConstructor
public class MenuBrowseController {

    /** Enough to cover a usual order without turning the rail into a second menu. */
    private static final int REORDER_RAIL_SIZE = 10;

    private final MenuService menuService;
    private final OrderService orderService;
    private final OutletService outletService;
    private final Cart cart;
    private final BusinessClock businessClock;

    @GetMapping
    public String browse(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) Long outletId, Model model) {
        User user = principal.getUser();
        List<Outlet> outlets = outletService.listActive(user.getTenantId());
        if (outlets.isEmpty()) {
            model.addAttribute("pageTitle", "Menu");
            model.addAttribute("outlets", outlets);
            return "student/menu";
        }

        Long requested = outletId != null ? outletId : cart.getOutletId();
        Optional<Outlet> match = requested == null ? Optional.empty()
                : outlets.stream().filter(o -> o.getId().equals(requested)).findFirst();

        if (match.isEmpty()) {
            if (outlets.size() > 1) {
                return "redirect:/student/menu/select";
            }
            match = Optional.of(outlets.get(0));
        }

        Outlet selected = match.get();
        cart.ensureOutlet(selected.getId());

        List<MenuItem> items = menuService.listAvailableForOutlet(selected.getId(), user.getTenantId());
        Map<String, List<MenuItem>> byCategory = items.stream()
                .collect(Collectors.groupingBy(MenuItem::getCategory, LinkedHashMap::new, Collectors.toList()));
        // A discount on something nobody can add to a cart is just an advert for
        // disappointment, so the specials rail skips anything sold out for the day.
        List<MenuItem> dealItems = items.stream()
                .filter(MenuItem::hasDiscount)
                .filter(MenuItem::orderable)
                .limit(10)
                .collect(Collectors.toList());

        model.addAttribute("outlets", outlets);
        model.addAttribute("selectedOutlet", selected);
        model.addAttribute("ordersOpen", selected.isAcceptingOrders());
        model.addAttribute("itemsByCategory", byCategory);
        model.addAttribute("dealItems", dealItems);
        model.addAttribute("reorderItems", reorderRail(user, selected, items));
        // Drives the Add-vs-stepper swap on each card: a card only shows a quantity
        // once that item is actually in the cart, so a stepper reading "1" always
        // means one is in there rather than "one is what you'd add".
        model.addAttribute("cartQuantities", cart.getQuantities());
        model.addAttribute("greeting", greeting());
        model.addAttribute("firstName", user.getName() == null ? null : user.getName().split(" ")[0]);
        model.addAttribute("pageTitle", "Menu");
        return "student/menu";
    }

    /**
     * The "Order again" rail: this student's usual order at this canteen, ready to add
     * in one tap.
     *
     * <p>Ranked by the database, then filtered against today's menu here, so anything the
     * canteen has since removed, turned off or sold out for the day drops out silently
     * instead of being offered and then refused at the cart. The ranking order survives
     * the filter, which a plain {@code items.stream().filter(ids::contains)} would have
     * thrown away by falling back to menu order.
     */
    private List<MenuItem> reorderRail(User user, Outlet outlet, List<MenuItem> todaysItems) {
        List<Long> ranked = orderService.frequentMenuItemIds(
                user.getId(), user.getTenantId(), outlet.getId(), REORDER_RAIL_SIZE);
        if (ranked.isEmpty()) {
            return List.of();
        }
        Map<Long, MenuItem> orderable = todaysItems.stream()
                .filter(MenuItem::orderable)
                .collect(Collectors.toMap(MenuItem::getId, i -> i, (a, b) -> a));
        return ranked.stream().map(orderable::get).filter(Objects::nonNull).toList();
    }

    /**
     * Single-item view. {@link MenuService#get} already scopes the lookup to the
     * caller's tenant, but that alone would still expose an item belonging to an
     * outlet the student cannot order from (a deactivated canteen, say), so the
     * outlet is re-checked against the active list and a miss is reported as
     * not-found rather than forbidden — a student has no business learning that
     * an id exists somewhere they cannot reach.
     */
    @GetMapping("/item/{itemId}")
    public String itemDetail(@AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long itemId, Model model) {
        User user = principal.getUser();
        MenuItem item = menuService.getWithTodayCount(itemId, user.getTenantId());

        Outlet outlet = outletService.listActive(user.getTenantId()).stream()
                .filter(o -> o.getId().equals(item.getOutletId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));

        model.addAttribute("item", item);
        model.addAttribute("outlet", outlet);
        model.addAttribute("pageTitle", item.getName());
        return "student/item-detail";
    }

    /**
     * Reads the canteen's clock, not the server's. {@code LocalTime.now()} here returned
     * UTC in production, which greeted students in India five and a half hours behind —
     * "Good afternoon" at 18:19.
     */
    private String greeting() {
        return greetingFor(businessClock.time().getHour());
    }

    /** Split out from the clock so the wording is testable without standing up a request. */
    static String greetingFor(int hour) {
        if (hour < 12) return "Good morning";
        if (hour < 17) return "Good afternoon";
        return "Good evening";
    }

    @GetMapping("/select")
    public String selectOutlet(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        User user = principal.getUser();
        model.addAttribute("outlets", outletService.listActive(user.getTenantId()));
        model.addAttribute("pageTitle", "Choose your canteen");
        return "student/select-outlet";
    }
}
