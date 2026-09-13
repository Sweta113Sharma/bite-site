package com.bitesite.controller;

import com.bitesite.service.PlatformSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class LegalController {

    private final PlatformSettingsService platformSettingsService;

    @GetMapping("/privacy-policy")
    public String privacyPolicy(Model model) {
        model.addAttribute("pageTitle", "Privacy policy");
        return "legal/privacy-policy";
    }

    @GetMapping("/account-deletion")
    public String accountDeletion(Model model) {
        model.addAttribute("pageTitle", "Delete your account");
        return "legal/account-deletion";
    }

    @GetMapping("/terms")
    public String terms(Model model) {
        model.addAttribute("pageTitle", "Terms of service");
        return "legal/terms";
    }

    @GetMapping("/refund-policy")
    public String refundPolicy(Model model) {
        model.addAttribute("pageTitle", "Refund policy");
        return "legal/refund-policy";
    }

    @GetMapping("/shipping-policy")
    public String shippingPolicy(Model model) {
        model.addAttribute("pageTitle", "Shipping & delivery");
        return "legal/shipping-policy";
    }

    @GetMapping("/grievance-policy")
    public String grievancePolicy(Model model) {
        // Read every time rather than cached: this page is the published legal contact,
        // and a super admin correcting a wrong address expects the correction to be live
        // on the next request, not after a restart or a cache expiry.
        model.addAttribute("officer", platformSettingsService.getGrievanceOfficer());
        model.addAttribute("pageTitle", "Grievance redressal");
        return "legal/grievance-policy";
    }
}
