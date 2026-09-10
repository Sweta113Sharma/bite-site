package com.bitesite.controller.admin;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.PortalGuard;
import com.bitesite.dto.analytics.AnalyticsFilter;
import com.bitesite.dto.analytics.AnalyticsReport;
import com.bitesite.model.Outlet;
import com.bitesite.model.StaffScope;
import com.bitesite.service.AnalyticsService;
import com.bitesite.service.OutletService;
import com.bitesite.service.TenantService;
import com.bitesite.tenant.Tenant;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Advanced analytics controller for college and canteen operational metrics.
 * Gated strictly to {@link StaffScope#FULL_ADMIN}.
 */
@Controller
@RequestMapping("/admin/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final TenantService tenantService;
    private final OutletService outletService;

    @GetMapping
    public String viewAnalytics(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false, defaultValue = "7d") String range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long outletId,
            Model model) {

        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);

        AnalyticsFilter filter = AnalyticsFilter.builder()
                .range(range)
                .fromDate(fromDate)
                .toDate(toDate)
                .tenantId(tenantId)
                .outletId(outletId)
                .build();

        AnalyticsReport report = analyticsService.generateReport(filter);

        List<Tenant> tenants = tenantService.listAll();
        List<Outlet> outlets = new ArrayList<>();
        for (Tenant tenant : tenants) {
            outlets.addAll(outletService.listAll(tenant.getId()));
        }

        model.addAttribute("report", report);
        model.addAttribute("filter", filter);
        model.addAttribute("tenants", tenants);
        model.addAttribute("outlets", outlets);
        model.addAttribute("pageTitle", "Analytics");
        model.addAttribute("currentPath", "/admin/analytics");

        return "admin/analytics";
    }
}
