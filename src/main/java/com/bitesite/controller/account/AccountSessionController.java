package com.bitesite.controller.account;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.UserSessionRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * "Sign out everywhere else."
 *
 * <p>Sessions are DB-backed and last 30 minutes of inactivity, so signing out on a shared
 * machine you no longer have in front of you was not something the product could do. This
 * is the same revocation a password change performs, offered on its own for the case where
 * the password is fine and it is a forgotten sign-in that is the problem.
 */
@Controller
@RequestMapping("/account/sessions")
@RequiredArgsConstructor
public class AccountSessionController {

    private final UserSessionRegistry userSessionRegistry;

    @PostMapping("/revoke-others")
    public String revokeOthers(@AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest request, RedirectAttributes redirectAttributes) {
        HttpSession session = request.getSession(false);
        userSessionRegistry.revokeOtherSessions(
                principal.getUser().getEmail(), session != null ? session.getId() : null);
        redirectAttributes.addFlashAttribute("sessionsRevoked", true);
        return "redirect:/account/password";
    }
}
