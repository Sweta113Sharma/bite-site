package com.bitesite.controller.admin;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.PortalGuard;
import com.bitesite.exception.BusinessException;
import com.bitesite.model.Outlet;
import com.bitesite.model.PromoCode;
import com.bitesite.model.StaffScope;
import com.bitesite.tenant.Tenant;
import com.bitesite.service.OutletService;
import com.bitesite.service.PromoCodeService;
import com.bitesite.service.TenantService;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Discount codes, run entirely from this screen.
 *
 * <p>Nothing about a campaign is compiled in: its worth, its ceiling, its minimum, who it
 * applies to, when it runs, how many times it can be used, and above all who pays for it
 * are all set here. That last one is the reason this screen exists separately from the
 * billing panel — see {@link PromoCode}.
 */
@Controller
@RequestMapping("/admin/billing/promo-codes")
@RequiredArgsConstructor
public class PromoCodeController {

    private final PromoCodeService promoCodeService;
    private final TenantService tenantService;
    private final OutletService outletService;

    @GetMapping
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        model.addAttribute("codes", promoCodeService.listAll());
        model.addAttribute("pageTitle", "Promo codes");
        return "admin/promo-codes";
    }

    @GetMapping("/new")
    public String create(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        return form(model, PromoCode.builder()
                .discountType(PromoCode.Type.FLAT)
                .fundedBy(PromoCode.Funder.PLATFORM)
                .active(true)
                .build(), "New promo code");
    }

    @GetMapping("/{id}/edit")
    public String edit(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        return form(model, promoCodeService.get(id), "Edit promo code");
    }

    /**
     * The scope pickers need every college and every canteen in one go.
     *
     * <p>Loaded whole and filtered in the browser rather than fetched per college. The list
     * is small, complete, and a select that has to wait on a request to populate is worse
     * to use than one that is simply already right.
     */
    private String form(Model model, PromoCode code, String title) {
        List<Tenant> tenants = tenantService.listAll();
        List<Outlet> outlets = new ArrayList<>();
        for (Tenant tenant : tenants) {
            outlets.addAll(outletService.listAll(tenant.getId()));
        }
        model.addAttribute("code", code);
        model.addAttribute("tenants", tenants);
        model.addAttribute("outlets", outlets);
        model.addAttribute("types", PromoCode.Type.values());
        model.addAttribute("funders", PromoCode.Funder.values());
        model.addAttribute("pageTitle", title);
        return "admin/promo-code-form";
    }

    @PostMapping
    public String save(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) Long id,
            @RequestParam String code,
            @RequestParam(required = false) String description,
            @RequestParam PromoCode.Type discountType,
            @RequestParam BigDecimal discountValue,
            @RequestParam(required = false) BigDecimal maxDiscount,
            @RequestParam(required = false) BigDecimal minOrderValue,
            @RequestParam PromoCode.Funder fundedBy,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long outletId,
            @RequestParam(required = false) String validFrom,
            @RequestParam(required = false) String validUntil,
            @RequestParam(required = false) Integer maxRedemptions,
            @RequestParam(required = false) Integer maxPerUser,
            @RequestParam(required = false) Boolean active,
            RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);

        // A canteen picked without its college would be a code scoped to a canteen but
        // offered at every college, which is not a scope anybody means.
        Long resolvedTenant = tenantId;
        if (outletId != null && resolvedTenant == null) {
            redirectAttributes.addFlashAttribute("error", "Pick the college that canteen belongs to.");
            return "redirect:/admin/billing/promo-codes/new";
        }

        PromoCode promo = PromoCode.builder()
                .id(id)
                .code(code)
                .description(blankToNull(description))
                .discountType(discountType)
                .discountValue(discountValue)
                .maxDiscount(maxDiscount)
                .minOrderValue(minOrderValue)
                .fundedBy(fundedBy)
                .tenantId(resolvedTenant)
                .outletId(outletId)
                .validFrom(parseDateTime(validFrom))
                .validUntil(parseDateTime(validUntil))
                .maxRedemptions(maxRedemptions)
                .maxPerUser(maxPerUser)
                .active(Boolean.TRUE.equals(active))
                .build();
        try {
            promoCodeService.save(promo, principal.getUser().getId());
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return id == null
                    ? "redirect:/admin/billing/promo-codes/new"
                    : "redirect:/admin/billing/promo-codes/" + id + "/edit";
        }
        redirectAttributes.addFlashAttribute("notice", "Saved " + promo.getCode().trim().toUpperCase() + ".");
        return "redirect:/admin/billing/promo-codes";
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            @RequestParam boolean active, RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        promoCodeService.setActive(id, active, principal.getUser().getId());
        redirectAttributes.addFlashAttribute("notice",
                active ? "That code is live again." : "That code is switched off. Orders already placed keep their discount.");
        return "redirect:/admin/billing/promo-codes";
    }

    @PostMapping("/{id}/delete")
    public String delete(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        try {
            promoCodeService.delete(id, principal.getUser().getId());
            redirectAttributes.addFlashAttribute("notice", "Code deleted.");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/billing/promo-codes";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** {@code datetime-local} posts "2026-09-10T18:30", or nothing at all when left empty. */
    private static LocalDateTime parseDateTime(String value) {
        return value == null || value.isBlank() ? null : LocalDateTime.parse(value);
    }
}
