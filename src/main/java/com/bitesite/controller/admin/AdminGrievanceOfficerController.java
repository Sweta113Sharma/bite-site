package com.bitesite.controller.admin;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.PortalGuard;
import com.bitesite.dto.GrievanceOfficerForm;
import com.bitesite.model.GrievanceOfficer;
import com.bitesite.model.StaffScope;
import com.bitesite.service.PlatformSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Lets a super admin publish the grievance officer named on {@code /grievance-policy}.
 *
 * <p>FULL_ADMIN rather than OPS_SCOPE, which is what the grievance *queue* uses: handling
 * complaints is day-to-day operations, whereas naming the officer publishes a legal
 * contact for the whole platform and puts a real person's name and postal address on a
 * public page. That is a SUPER_ADMIN decision, not a tech manager's.
 */
@Controller
@RequestMapping("/admin/grievance-officer")
@RequiredArgsConstructor
public class AdminGrievanceOfficerController {

    private final PlatformSettingsService platformSettingsService;

    @GetMapping
    public String edit(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", toForm(platformSettingsService.getGrievanceOfficer()));
        }
        model.addAttribute("officer", platformSettingsService.getGrievanceOfficer());
        model.addAttribute("pageTitle", "Grievance officer");
        return "admin/grievance-officer";
    }

    @PostMapping
    public String save(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") GrievanceOfficerForm form, BindingResult bindingResult,
            Model model, RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        if (bindingResult.hasErrors()) {
            model.addAttribute("officer", platformSettingsService.getGrievanceOfficer());
            model.addAttribute("pageTitle", "Grievance officer");
            return "admin/grievance-officer";
        }
        platformSettingsService.saveGrievanceOfficer(
                new GrievanceOfficer(form.getName().trim(), trimOrNull(form.getDesignation()),
                        form.getEmail().trim(), form.getAddress().trim(), form.getResponseWindow().trim()),
                principal.getUser().getId());
        redirectAttributes.addFlashAttribute("saved", true);
        return "redirect:/admin/grievance-officer";
    }

    private static String trimOrNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static GrievanceOfficerForm toForm(GrievanceOfficer officer) {
        GrievanceOfficerForm form = new GrievanceOfficerForm();
        form.setName(officer.name());
        form.setDesignation(officer.designation());
        form.setEmail(officer.email());
        form.setAddress(officer.address());
        form.setResponseWindow(officer.responseWindow());
        return form;
    }
}
