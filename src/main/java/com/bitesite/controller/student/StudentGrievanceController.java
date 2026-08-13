package com.bitesite.controller.student;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.dto.GrievanceForm;
import com.bitesite.model.User;
import com.bitesite.service.GrievanceService;
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

@Controller
@RequestMapping("/student/grievances")
@RequiredArgsConstructor
public class StudentGrievanceController {

    private final GrievanceService grievanceService;

    @GetMapping
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        User user = principal.getUser();
        model.addAttribute("grievances", grievanceService.listForUser(user.getId(), user.getTenantId()));
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new GrievanceForm());
        }
        model.addAttribute("pageTitle", "Support");
        return "student/grievances";
    }

    @PostMapping
    public String raise(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") GrievanceForm form, BindingResult bindingResult, Model model) {
        User user = principal.getUser();
        if (bindingResult.hasErrors()) {
            model.addAttribute("grievances", grievanceService.listForUser(user.getId(), user.getTenantId()));
            model.addAttribute("pageTitle", "Support");
            return "student/grievances";
        }
        grievanceService.raise(user.getTenantId(), user.getId(), form.getSubject(), form.getMessage());
        return "redirect:/student/grievances";
    }
}
