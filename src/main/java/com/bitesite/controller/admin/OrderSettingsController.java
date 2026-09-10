package com.bitesite.controller.admin;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.PortalGuard;
import com.bitesite.model.OrderSettings;
import com.bitesite.model.StaffScope;
import com.bitesite.service.PlatformSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * How orders behave, as opposed to what they cost.
 *
 * <p>Sits beside the order list rather than in the billing panel: this is an operational
 * rule, not a commercial term. It is gated to full admin all the same, because the window
 * it sets is the one in which a student is owed an automatic refund.
 */
@Controller
@RequestMapping("/admin/orders/settings")
@RequiredArgsConstructor
public class OrderSettingsController {

    private final PlatformSettingsService platformSettingsService;

    @GetMapping
    public String settings(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        model.addAttribute("settings", platformSettingsService.getOrderSettings());
        model.addAttribute("maxWindow", OrderSettings.MAX_SELF_CANCEL_WINDOW_SECONDS);
        model.addAttribute("defaultWindow", OrderSettings.DEFAULT_SELF_CANCEL_WINDOW_SECONDS);
        model.addAttribute("pageTitle", "Order settings");
        return "admin/order-settings";
    }

    /**
     * Parsed here rather than bound straight to an {@code int}, because a mistyped digit
     * would otherwise leave Spring to throw a type mismatch that lands in the generic
     * handler as a 500 and a Sentry event. A number typed wrong is a form error, and should
     * read like one.
     */
    @PostMapping
    public String save(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(name = "selfCancelWindowSeconds", defaultValue = "") String requested,
            RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);

        int seconds;
        try {
            seconds = Integer.parseInt(requested.trim());
        } catch (NumberFormatException e) {
            redirectAttributes.addFlashAttribute("error",
                    "Enter the cancellation window as a whole number of seconds.");
            return "redirect:/admin/orders/settings";
        }

        int saved = platformSettingsService.saveSelfCancelWindow(seconds, principal.getUser().getId());
        // Always states the stored number rather than the typed one, so a clamped value is
        // seen instead of quietly applied.
        redirectAttributes.addFlashAttribute("notice", saved == 0
                ? "Saved. Students can no longer cancel their own orders, and the kitchen sees every order as soon as it is paid for."
                : "Saved. Students can cancel for " + saved + " seconds after paying, and the kitchen sees the order once that passes.");
        return "redirect:/admin/orders/settings";
    }
}
