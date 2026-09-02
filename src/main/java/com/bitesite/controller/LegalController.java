package com.bitesite.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LegalController {

    @GetMapping("/privacy-policy")
    public String privacyPolicy(Model model) {
        model.addAttribute("pageTitle", "Privacy policy");
        return "legal/privacy-policy";
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
        model.addAttribute("pageTitle", "Grievance redressal");
        return "legal/grievance-policy";
    }
}
