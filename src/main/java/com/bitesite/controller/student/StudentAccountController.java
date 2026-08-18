package com.bitesite.controller.student;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.model.User;
import com.bitesite.service.UserService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/student/account")
@RequiredArgsConstructor
public class StudentAccountController {

    private final UserService userService;

    @GetMapping
    public String view(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        model.addAttribute("user", principal.getUser());
        model.addAttribute("pageTitle", "My account");
        return "student/account";
    }

    @PostMapping("/delete")
    public String delete(@AuthenticationPrincipal AppUserPrincipal principal, HttpServletRequest request)
            throws ServletException {
        User user = principal.getUser();
        userService.deleteOwnAccount(user.getId(), user.getTenantId());
        request.logout();
        return "redirect:/login?accountDeleted";
    }
}
