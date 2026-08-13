package com.bitesite.controller.admin;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.service.GrievanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/admin/grievances")
@RequiredArgsConstructor
public class AdminGrievanceController {

    private final GrievanceService grievanceService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("grievances", grievanceService.listAll());
        model.addAttribute("pageTitle", "Grievances");
        return "admin/grievances";
    }

    @PostMapping("/{id}/resolve")
    public String resolve(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            @RequestParam String response) {
        grievanceService.resolve(id, response, principal.getUser().getId());
        return "redirect:/admin/grievances";
    }
}
