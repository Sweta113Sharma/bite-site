package com.bitesite.controller.auth;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.RoleLandingPages;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("pageTitle", "Sign in");
        return "auth/login";
    }

    @GetMapping("/")
    public String home(Model model) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AppUserPrincipal principal) {
            return "redirect:" + RoleLandingPages.forActiveRole(principal.getUser().getActiveRole());
        }
        // Anonymous visitors get the welcome screen rather than being dropped straight
        // into the sign-in form, so the first thing a new user sees offers registering
        // as plainly as signing in.
        model.addAttribute("pageTitle", "Welcome");
        return "auth/welcome";
    }
}
