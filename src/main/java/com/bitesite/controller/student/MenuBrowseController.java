package com.bitesite.controller.student;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.model.MenuItem;
import com.bitesite.model.Outlet;
import com.bitesite.model.User;
import com.bitesite.service.Cart;
import com.bitesite.service.MenuService;
import com.bitesite.service.OutletService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final MenuService menuService;
    private final OutletService outletService;
    private final Cart cart;

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

        model.addAttribute("outlets", outlets);
        model.addAttribute("selectedOutlet", selected);
        model.addAttribute("itemsByCategory", byCategory);
        model.addAttribute("pageTitle", "Menu");
        return "student/menu";
    }

    @GetMapping("/select")
    public String selectOutlet(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        User user = principal.getUser();
        model.addAttribute("outlets", outletService.listActive(user.getTenantId()));
        model.addAttribute("pageTitle", "Choose your canteen");
        return "student/select-outlet";
    }
}
