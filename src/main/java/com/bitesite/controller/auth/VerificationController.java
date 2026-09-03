package com.bitesite.controller.auth;

import com.bitesite.config.RateLimiter;
import com.bitesite.model.OtpChannel;
import com.bitesite.model.User;
import com.bitesite.service.OtpService;
import com.bitesite.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * OTP-based email + phone verification. Unlike the old link-based flow, a 6-digit code
 * can't identify the account on its own (too small a keyspace to embed identity safely),
 * so which account is being verified is tracked via a session attribute
 * ({@link #PENDING_USER_ID}) set right after registration or after a successful
 * {@link #resendLookup} match — never via a URL parameter.
 */
@Controller
@RequiredArgsConstructor
public class VerificationController {

    /** Public because changing your phone number under {@code /account} has to hand the
     * user to this flow, and that controller is in another package. */
    public static final String PENDING_USER_ID = "pendingVerificationUserId";

    private static final int MAX_RESENDS = 3;
    private static final Duration RESEND_WINDOW = Duration.ofMinutes(15);

    private final OtpService otpService;
    private final UserService userService;
    private final RateLimiter rateLimiter;

    @GetMapping("/verify")
    public String show(HttpSession session, Model model) {
        Optional<User> pending = pendingUser(session);
        if (pending.isEmpty()) {
            return "redirect:/resend-verification";
        }
        User user = pending.get();
        if (user.isEmailVerified() && user.isPhoneVerified()) {
            session.removeAttribute(PENDING_USER_ID);
            return "redirect:/login?verified";
        }
        model.addAttribute("needsEmail", !user.isEmailVerified());
        model.addAttribute("needsPhone", !user.isPhoneVerified());
        model.addAttribute("pageTitle", "Verify your account");
        return "auth/verify";
    }

    @PostMapping("/verify/email")
    public String verifyEmail(@RequestParam String code, HttpSession session, RedirectAttributes redirectAttributes) {
        return handleVerify(OtpChannel.EMAIL, code, session, redirectAttributes);
    }

    @PostMapping("/verify/phone")
    public String verifyPhone(@RequestParam String code, HttpSession session, RedirectAttributes redirectAttributes) {
        return handleVerify(OtpChannel.PHONE, code, session, redirectAttributes);
    }

    @PostMapping("/verify/resend")
    public String resend(@RequestParam OtpChannel channel, HttpSession session, RedirectAttributes redirectAttributes) {
        Optional<User> pending = pendingUser(session);
        if (pending.isEmpty()) {
            return "redirect:/resend-verification";
        }
        User user = pending.get();
        String key = "verify-resend:" + user.getId() + ":" + channel;
        if (rateLimiter.tryConsume(key, MAX_RESENDS, RESEND_WINDOW)) {
            if (channel == OtpChannel.EMAIL) {
                otpService.issueEmailOtp(user);
            } else {
                otpService.issuePhoneOtp(user);
            }
            redirectAttributes.addFlashAttribute("resent", true);
        } else {
            redirectAttributes.addFlashAttribute("rateLimited", true);
        }
        return "redirect:/verify";
    }

    @GetMapping("/resend-verification")
    public String showResendForm(Model model) {
        model.addAttribute("pageTitle", "Resend verification code");
        return "auth/resend-verification";
    }

    /** Always redirects to the same place regardless of whether the email matches an
     * account, is already fully verified, or is rate-limited — never lets this endpoint be
     * used to probe which emails are registered. Only the session state set behind the
     * scenes differs, which the client can't observe without already knowing the outcome. */
    @PostMapping("/resend-verification")
    public String resendLookup(@RequestParam String email, HttpSession session) {
        userService.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                .filter(user -> !user.isEmailVerified() || !user.isPhoneVerified())
                .ifPresent(user -> {
                    session.setAttribute(PENDING_USER_ID, user.getId());
                    if (!user.isEmailVerified()) {
                        otpService.issueEmailOtp(user);
                    }
                    if (!user.isPhoneVerified()) {
                        otpService.issuePhoneOtp(user);
                    }
                });
        return "redirect:/verify";
    }

    private String handleVerify(OtpChannel channel, String code, HttpSession session,
            RedirectAttributes redirectAttributes) {
        Optional<User> pending = pendingUser(session);
        if (pending.isEmpty()) {
            return "redirect:/resend-verification";
        }
        boolean verified = otpService.verify(pending.get().getId(), channel, code);
        if (!verified) {
            redirectAttributes.addFlashAttribute(channel == OtpChannel.EMAIL ? "emailError" : "phoneError", true);
        }
        return "redirect:/verify";
    }

    private Optional<User> pendingUser(HttpSession session) {
        Object id = session.getAttribute(PENDING_USER_ID);
        if (!(id instanceof Long userId)) {
            return Optional.empty();
        }
        return userService.findById(userId);
    }
}
