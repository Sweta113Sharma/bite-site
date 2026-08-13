package com.bitesite.controller.admin;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.dto.OnboardingLeadForm;
import com.bitesite.model.OnboardingStage;
import com.bitesite.service.OnboardingService;
import com.bitesite.tenant.Tenant;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("leads", onboardingService.listAll());
        model.addAttribute("stages", OnboardingStage.values());
        model.addAttribute("pageTitle", "Onboarding pipeline");
        return "admin/onboarding";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new OnboardingLeadForm());
        }
        model.addAttribute("pageTitle", "Add a lead");
        return "admin/onboarding-form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") OnboardingLeadForm form, BindingResult bindingResult,
            Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("pageTitle", "Add a lead");
            return "admin/onboarding-form";
        }
        onboardingService.createLead(form.getCollegeName(), form.getContactName(), form.getContactEmail(),
                form.getContactPhone(), form.getNotes());
        return "redirect:/admin/onboarding";
    }

    @PostMapping("/{id}/stage")
    public String advance(@PathVariable Long id, @RequestParam OnboardingStage stage) {
        onboardingService.advanceStage(id, stage);
        return "redirect:/admin/onboarding";
    }

    @PostMapping("/{id}/convert")
    public String convert(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id) {
        Tenant tenant = onboardingService.convertToTenant(id, principal.getUser().getId());
        return "redirect:/admin/tenants/" + tenant.getId();
    }
}
