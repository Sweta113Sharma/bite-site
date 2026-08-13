package com.bitesite.controller;

import com.bitesite.config.TenantContext;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class SiteController {

    @GetMapping("/tenant-unavailable")
    public String tenantUnavailable(Model model) {
        model.addAttribute("pageTitle", "Site unavailable");
        model.addAttribute("tenant", TenantContext.get());
        return "error/tenant-unavailable";
    }

    // No method restriction: Spring Security's CSRF-failure handler forwards the
    // *original* request method here (a rejected POST stays a POST across the forward),
    // so a GET-only mapping would turn a CSRF failure into a confusing 405.
    @RequestMapping("/access-denied")
    public String accessDenied(Model model) {
        model.addAttribute("pageTitle", "Access denied");
        return "error/access-denied";
    }
}
