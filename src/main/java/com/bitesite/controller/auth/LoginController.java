package com.bitesite.controller.auth;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.PortalResolver;
import com.bitesite.config.RoleLandingPages;
import com.bitesite.model.PortalTarget;
import com.bitesite.web.MobileClient;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class LoginController {

    private final PortalResolver portalResolver;

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
        // Customer portal only. The screen sells ordering lunch and its second button is
        // "Create account", which on the outlet or admin host was inviting a canteen
        // manager to register themselves as a student — an account their portal then
        // refuses to admit. Staff arrive knowing what this is and already holding an
        // account someone else made for them, so they get the form.
        //
        // And phones only. It is an app screen — full-bleed hero, tap targets, a
        // scrolling stack — and a desktop window renders it as a narrow column marooned
        // in empty space next to a brand panel repeating its own headline.
        if (portalResolver.resolve(request) != PortalTarget.APP || !MobileClient.isMobile(request)) {
            return "redirect:/login";
        }
        model.addAttribute("pageTitle", "Welcome");
        return "auth/welcome";
    }
}
