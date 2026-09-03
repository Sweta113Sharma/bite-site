package com.bitesite.controller.auth;

import com.bitesite.config.RateLimiter;
import com.bitesite.config.UserSessionRegistry;
import com.bitesite.dto.ResetPasswordForm;
import com.bitesite.model.User;
import com.bitesite.service.OtpService;
import com.bitesite.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Forgotten-password recovery: prove you hold the account's mailbox with a 6-digit code,
 * then choose a new password. Until this existed, a forgotten password meant the account
 * was gone — there was no self-service path and no admin-initiated one either, so the only
 * remedy was deleting the account and registering again, which also abandoned the order
 * history attached to it.
 *
 * <p>Like {@link VerificationController}, the code is too small a keyspace to identify an
 * account on its own, so which account is being reset is tracked in the session
 * ({@link #PENDING_RESET_EMAIL}) and never in a URL parameter.
 *
 * <h2>Not leaking which emails are registered</h2>
 * The session attribute is set for <em>any</em> syntactically plausible email, whether or
 * not an account exists, and {@code /reset-password} therefore renders identically either
 * way. This is stricter than the older {@code resend-verification} flow, which sets its
 * session attribute only on a match: there, an unregistered address bounces off
 * {@code /verify} straight back to the lookup form, and that difference in where you land
 * is itself an answer to "does this person have an account here". A wrong code and a
 * non-existent account produce the same message here for the same reason.
 */
@Controller
@RequiredArgsConstructor
public class PasswordResetController {

    /** The address a code was requested for. A String rather than a user id precisely so
     * it can be set without knowing whether an account exists. */
    static final String PENDING_RESET_EMAIL = "pendingResetEmail";

    // IP-keyed, matching registration's shape: loose enough for a whole college behind one
    // campus NAT, tight enough that the endpoint is not a free mail cannon.
    private static final int MAX_REQUESTS_PER_IP = 20;
    private static final int MAX_REQUESTS_PER_EMAIL = 3;
    private static final Duration REQUEST_WINDOW = Duration.ofMinutes(15);

    // Backstop under OtpService's own 5-attempts-per-code cap. That cap resets when a new
    // code is issued, so without this a caller could alternate "request code / burn five
    // guesses" indefinitely. Combined with the per-email request limit, guessing is held to
    // a few dozen tries per window against a million-value keyspace.
    private static final int MAX_SUBMISSIONS = 15;

    private final UserService userService;
    private final OtpService otpService;
    private final RateLimiter rateLimiter;
    private final UserSessionRegistry userSessionRegistry;

    @GetMapping("/forgot-password")
    public String showRequestForm(Model model) {
        model.addAttribute("pageTitle", "Forgot password");
        return "auth/forgot-password";
    }

    /**
     * Always ends up in the same place with the same page. The only thing that varies is
     * whether a mail was actually sent, which the caller cannot observe.
     */
    @PostMapping("/forgot-password")
    public String requestCode(@RequestParam String email, HttpServletRequest request, HttpSession session) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        session.setAttribute(PENDING_RESET_EMAIL, normalized);

        boolean withinIpLimit = rateLimiter.tryConsume(
                "pwreset-request:" + request.getRemoteAddr(), MAX_REQUESTS_PER_IP, REQUEST_WINDOW);
        // Per-address as well as per-IP: without it, one person's inbox can be filled with
        // reset codes from a rotating set of source addresses.
        boolean withinEmailLimit = rateLimiter.tryConsume(
                "pwreset-email:" + normalized, MAX_REQUESTS_PER_EMAIL, REQUEST_WINDOW);

        if (withinIpLimit && withinEmailLimit) {
            userService.findByEmail(normalized)
                    // A deactivated or already-deleted account cannot sign in even with a
                    // fresh password, so there is nothing to send it.
                    .filter(User::isActive)
                    .ifPresent(otpService::issuePasswordResetOtp);
        }
        return "redirect:/reset-password";
    }

    @GetMapping("/reset-password")
    public String showResetForm(HttpSession session, Model model) {
        if (session.getAttribute(PENDING_RESET_EMAIL) == null) {
            return "redirect:/forgot-password";
        }
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new ResetPasswordForm());
        }
        model.addAttribute("pageTitle", "Choose a new password");
        return "auth/reset-password";
    }

    @PostMapping("/reset-password")
    public String reset(@Valid @ModelAttribute("form") ResetPasswordForm form, BindingResult bindingResult,
            HttpSession session, Model model) {
        if (!(session.getAttribute(PENDING_RESET_EMAIL) instanceof String email)) {
            return "redirect:/forgot-password";
        }
        if (!bindingResult.hasErrors() && !form.confirmationMatches()) {
            bindingResult.rejectValue("confirmPassword", "mismatch", "Those passwords don't match");
        }
        if (bindingResult.hasErrors()) {
            model.addAttribute("pageTitle", "Choose a new password");
            return "auth/reset-password";
        }
        if (!rateLimiter.tryConsume("pwreset-submit:" + email, MAX_SUBMISSIONS, REQUEST_WINDOW)) {
            model.addAttribute("rateLimited", true);
            model.addAttribute("pageTitle", "Choose a new password");
            return "auth/reset-password";
        }

        Optional<User> account = userService.findByEmail(email).filter(User::isActive);
        // One message for "no such account", "code expired", and "wrong code" — see the
        // class comment. Splitting them would turn this form into an account oracle.
        if (account.isEmpty() || !otpService.verifyPasswordReset(account.get().getId(), form.getCode())) {
            model.addAttribute("codeError", true);
            model.addAttribute("pageTitle", "Choose a new password");
            return "auth/reset-password";
        }

        userService.resetPassword(account.get().getId(), form.getNewPassword());
        // Whoever was using the old password may still hold a live session; a reset is
        // usually a response to exactly that.
        userSessionRegistry.revokeAllSessions(email);
        session.removeAttribute(PENDING_RESET_EMAIL);
        return "redirect:/login?passwordReset";
    }
}
