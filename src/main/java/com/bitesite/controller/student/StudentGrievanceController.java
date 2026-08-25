package com.bitesite.controller.student;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.RateLimiter;
import com.bitesite.dto.GrievanceForm;
import com.bitesite.model.User;
import com.bitesite.service.GrievanceService;
import com.bitesite.service.OrderService;
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

import java.time.Duration;

@Controller
@RequestMapping("/student/grievances")
@RequiredArgsConstructor
public class StudentGrievanceController {

    private static final int MAX_PER_WINDOW = 5;
    private static final Duration WINDOW = Duration.ofHours(1);

    private final GrievanceService grievanceService;
    private final OrderService orderService;
    private final RateLimiter rateLimiter;

    @GetMapping
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        User user = principal.getUser();
        model.addAttribute("grievances", grievanceService.listForUser(user.getId(), user.getTenantId()));
        model.addAttribute("orders", orderService.historyForUser(user.getId(), user.getTenantId()));
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
            model.addAttribute("orders", orderService.historyForUser(user.getId(), user.getTenantId()));
            model.addAttribute("pageTitle", "Support");
            return "student/grievances";
        }
        if (!rateLimiter.tryConsume("grievance:" + user.getId(), MAX_PER_WINDOW, WINDOW)) {
            model.addAttribute("grievances", grievanceService.listForUser(user.getId(), user.getTenantId()));
            model.addAttribute("orders", orderService.historyForUser(user.getId(), user.getTenantId()));
            model.addAttribute("rateLimited", true);
            model.addAttribute("pageTitle", "Support");
            return "student/grievances";
        }
        grievanceService.raise(user.getTenantId(), user.getId(), form.getOrderId(),
                form.getSubject(), form.getMessage());
        return "redirect:/student/grievances";
    }
}
