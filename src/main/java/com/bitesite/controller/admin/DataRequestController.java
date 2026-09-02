package com.bitesite.controller.admin;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.PortalGuard;
import com.bitesite.model.StaffScope;
import com.bitesite.privacy.DataRequest;
import com.bitesite.privacy.PrivacyService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/**
 * The data-principal request queue — the operational side of the "your rights" section of
 * the privacy policy. OPS_SCOPE, matching the grievance inbox it sits beside.
 */
@Controller
@RequestMapping("/admin/dpdp")
@RequiredArgsConstructor
public class DataRequestController {

    private static final int LIST_LIMIT = 100;

    private final PrivacyService privacyService;

    @GetMapping
    public String queue(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) DataRequest.Status status, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OPS_SCOPE);
        model.addAttribute("requests", privacyService.queue(status, LIST_LIMIT));
        model.addAttribute("statuses", DataRequest.Status.values());
        model.addAttribute("selectedStatus", status);
        model.addAttribute("pageTitle", "Data requests");
        return "admin/data-requests";
    }

    @PostMapping("/{id}/status")
    public String setStatus(@AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id, @RequestParam DataRequest.Status status) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OPS_SCOPE);
        privacyService.setRequestStatus(id, status, principal.getUser().getId());
        return "redirect:/admin/dpdp";
    }
}
