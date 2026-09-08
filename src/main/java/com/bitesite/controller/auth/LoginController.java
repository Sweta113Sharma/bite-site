package com.bitesite.controller.auth;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.RoleLandingPages;
import com.bitesite.web.MobileClient;
import jakarta.servlet.http.HttpServletRequest;
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
    public String home(Model model, HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AppUserPrincipal principal) {
            return "redirect:" + RoleLandingPages.forActiveRole(principal.getUser().getActiveRole());
        }
        // Anonymous visitors get the welcome screen rather than being dropped straight
        // into the sign-in form, so the first thing a new user sees offers registering
        // as plainly as signing in.
        //
        // Phones only. It is an app screen — full-bleed hero, tap targets, a scrolling
        // stack — and a desktop window renders it as a narrow column marooned in empty
        // space next to a brand panel repeating its own headline. Desktop visitors get
        // the sign-in form, which is what that width is designed for and what someone
        // who already has an account came for anyway.
        if (!MobileClient.isMobile(request)) {
            return "redirect:/login";
        }
        model.addAttribute("pageTitle", "Welcome");
        return "auth/welcome";
    }
}
