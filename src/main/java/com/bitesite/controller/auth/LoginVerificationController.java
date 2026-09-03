package com.bitesite.controller.auth;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.RateLimiter;
import com.bitesite.config.RoleBasedAuthenticationSuccessHandler;
import com.bitesite.config.RoleLandingPages;
import com.bitesite.model.User;
import com.bitesite.service.OtpService;
import com.bitesite.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.util.Optional;

/**
 * The second step of signing in to a platform account: the emailed code.
 *
 * <p>Reached only from {@link RoleBasedAuthenticationSuccessHandler}, which has already
 * checked the password and then deliberately thrown the authenticated session away. The
 * session that arrives here holds nothing but a user id, so a caller who guesses this URL
 * gets sent back to the login page — the password is still the first half of the check.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class LoginVerificationController {

    // Under OtpService's own five-attempts-per-code cap. That cap resets when a new code is
    // issued, and a new code needs the password again, so this is the ceiling that matters.
    private static final int MAX_ATTEMPTS = 10;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final UserService userService;
    private final OtpService otpService;
    private final RateLimiter rateLimiter;
    private final SecurityContextRepository securityContextRepository;

    @GetMapping("/login/verify")
    public String show(HttpSession session, Model model) {
        if (pendingUser(session).isEmpty()) {
            return "redirect:/login";
        }
        model.addAttribute("pageTitle", "Sign-in code");
        return "auth/login-verify";
    }

    @PostMapping("/login/verify")
    public String verify(@RequestParam String code, HttpSession session, HttpServletRequest request,
            HttpServletResponse response, RedirectAttributes redirectAttributes) {
        Optional<User> pending = pendingUser(session);
        if (pending.isEmpty()) {
            return "redirect:/login";
        }
        User user = pending.get();

        if (!rateLimiter.tryConsume("login2fa:" + user.getId(), MAX_ATTEMPTS, WINDOW)) {
            redirectAttributes.addFlashAttribute("rateLimited", true);
            return "redirect:/login/verify";
        }
        if (!otpService.verifyLoginOtp(user.getId(), code)) {
            redirectAttributes.addFlashAttribute("codeError", true);
            return "redirect:/login/verify";
        }

        // Only now does a session become authenticated. The id is removed first so a
        // replay of this request cannot mint a second session from the same pending state.
        session.removeAttribute(RoleBasedAuthenticationSuccessHandler.PENDING_2FA_USER_ID);
        AppUserPrincipal principal = new AppUserPrincipal(user);
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, principal.getPassword(), principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        log.info("Second factor accepted for platform account {}", user.getEmail());
        return "redirect:" + RoleLandingPages.forActiveRole(user.getActiveRole());
    }

    private Optional<User> pendingUser(HttpSession session) {
        Object id = session.getAttribute(RoleBasedAuthenticationSuccessHandler.PENDING_2FA_USER_ID);
        if (!(id instanceof Long userId)) {
            return Optional.empty();
        }
        return userService.findById(userId);
    }
}
