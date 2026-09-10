package com.bitesite.controller.admin;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.BusinessClock;
import com.bitesite.config.PortalGuard;
import com.bitesite.model.BillingSettings;
import com.bitesite.model.Settlement;
import com.bitesite.model.StaffScope;
import com.bitesite.service.BillingService;
import com.bitesite.service.PlatformSettingsService;
import com.bitesite.service.SettlementService;
import com.bitesite.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The platform's commercial controls, and the money they produce.
 *
 * <p>Two screens rather than one, because they answer different questions. Billing is
 * "what are the terms"; settlement is "what did those terms earn, and what do I owe".
 * Mixing a settings form into a payout report makes both harder to read and invites
 * editing a rate while looking at the money it produced.
 *
 * <p>Nothing here is hardcoded. Every rate, amount, label and piece of copy on both the
 * invoice and the tip dialog is set from this panel, and the seeded defaults are all inert
 * so the platform charges nothing until somebody decides otherwise.
 */
@Controller
@RequestMapping("/admin/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;
    private final BusinessClock businessClock;
    private final PlatformSettingsService platformSettingsService;
    private final SettlementService settlementService;
    private final TenantService tenantService;

    @GetMapping
    public String settings(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        model.addAttribute("settings", billingService.settings());
        model.addAttribute("pageTitle", "Billing settings");
        return "admin/billing";
    }

    @PostMapping
    public String save(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam Map<String, String> form, RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);

        // Only the keys this panel owns are written. Posting the whole form map straight
        // into the settings table would let a crafted field set anything in it, including
        // the grievance officer that shares the table.
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : List.of(
                BillingSettings.COMMISSION_PERCENT, BillingSettings.FEE_AMOUNT,
                BillingSettings.FEE_LABEL, BillingSettings.GST_PERCENT, BillingSettings.GST_NOTE,
                BillingSettings.TIP_TITLE, BillingSettings.TIP_MESSAGE, BillingSettings.TIP_AMOUNTS,
                BillingSettings.INVOICE_FOOTER)) {
            if (form.containsKey(key)) {
                values.put(key, form.get(key).trim());
            }
        }
        // An unchecked checkbox posts nothing at all, so each one is written explicitly
        // from whether its key arrived. Reading them like the text fields above would make
        // turning a switch off impossible.
        for (String key : List.of(BillingSettings.FEE_CHARGE, BillingSettings.FEE_SHOW_WHEN_FREE,
                BillingSettings.GST_SHOW, BillingSettings.TIP_ENABLED)) {
            values.put(key, String.valueOf(form.containsKey(key)));
        }

        platformSettingsService.saveBillingSettings(values, principal.getUser().getId());
        redirectAttributes.addFlashAttribute("notice",
                "Saved. These terms apply to new orders; existing orders keep the terms they were placed on.");
        return "redirect:/admin/billing";
    }

    /**
     * The payout ledger. Every rupee lands in one Razorpay account, so this reads as what
     * the platform owes each canteen, not what it bills them.
     */
    @GetMapping("/settlements")
    public String settlements(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) SettlementService.Period period,
            @RequestParam(required = false) Long tenantId,
            Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        SettlementService.Period selected = period != null ? period : SettlementService.Period.THIRTY_DAYS;

        List<Settlement> rows = settlementService.forPeriod(selected, tenantId, businessClock.today());

        model.addAttribute("rows", rows);
        model.addAttribute("periods", SettlementService.Period.values());
        model.addAttribute("selectedPeriod", selected);
        model.addAttribute("tenants", tenantService.listAll());
        model.addAttribute("selectedTenantId", tenantId);
        model.addAttribute("totals", totals(rows));
        model.addAttribute("pageTitle", "Settlements");
        return "admin/settlements";
    }

    /** Column sums, so the screen never adds up money in a template. */
    private Map<String, BigDecimal> totals(List<Settlement> rows) {
        Map<String, BigDecimal> t = new LinkedHashMap<>();
        t.put("food", rows.stream().map(Settlement::foodTotal).reduce(BigDecimal.ZERO, BigDecimal::add));
        t.put("commission", rows.stream().map(Settlement::commission).reduce(BigDecimal.ZERO, BigDecimal::add));
        t.put("netPayable", rows.stream().map(Settlement::netPayable).reduce(BigDecimal.ZERO, BigDecimal::add));
        t.put("fees", rows.stream().map(Settlement::platformFees).reduce(BigDecimal.ZERO, BigDecimal::add));
        t.put("tips", rows.stream().map(Settlement::tips).reduce(BigDecimal.ZERO, BigDecimal::add));
        t.put("platformDiscounts", rows.stream().map(Settlement::platformDiscounts)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        t.put("collected", rows.stream().map(Settlement::collected).reduce(BigDecimal.ZERO, BigDecimal::add));
        return t;
    }
}
