package com.bitesite.controller.account;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.RateLimiter;
import com.bitesite.config.UserSessionRegistry;
import com.bitesite.dto.EmailChangeForm;
import com.bitesite.exception.BusinessException;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import com.bitesite.service.OtpService;
import com.bitesite.service.UserService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;

/**
 * Changing the address you sign in with.
 *
 * <p>The other profile fields are edited in place; this one cannot be, because it is the
 * login identifier and it is unique across the platform. Typing an address you do not own
 * and having it applied immediately would lock you out of your own account. So the new
 * address is staged, a code goes to it, and only entering that code moves it across —
 * proving the mailbox before it becomes the thing that opens the account.
 */
@Controller
@RequestMapping("/account/email")
@RequiredArgsConstructor
public class EmailChangeController {

    // Per account, not per IP: this endpoint sends mail to an address the requester
    // chooses, so the abuse to prevent is using someone's account as a relay.
    private static final int MAX_REQUESTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final UserService userService;
    private final OtpService otpService;
    private final RateLimiter rateLimiter;
    private final UserSessionRegistry userSessionRegistry;

    @GetMapping
    public String show(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new EmailChangeForm());
        }
        addContext(model, principal.getUser());
        return "account/email";
    }

    @PostMapping
    public String request(@Valid @ModelAttribute("form") EmailChangeForm form, BindingResult bindingResult,
            @AuthenticationPrincipal AppUserPrincipal principal, Model model,
            RedirectAttributes redirectAttributes) {
        User user = principal.getUser();
        if (!bindingResult.hasErrors()
                && !rateLimiter.tryConsume("email-change:" + user.getId(), MAX_REQUESTS, WINDOW)) {
            bindingResult.reject("ratelimit", "Too many attempts — please wait a few minutes.");
        }
        if (!bindingResult.hasErrors()) {
            try {
                userService.requestEmailChange(user.getId(), form.getNewEmail(), form.getCurrentPassword());
                redirectAttributes.addFlashAttribute("codeSent", true);
                return "redirect:/account/email";
            } catch (BusinessException e) {
                bindingResult.reject("failed", e.getMessage());
            }
        }
        addContext(model, refreshed(user));
        return "account/email";
    }

    /**
     * Confirms the staged address, then signs every session out — including this one.
     *
     * <p>The session's principal is keyed by the old address and Spring Session indexes it
     * that way, so leaving it alive would leave a session authenticated as an identity the
     * account no longer has. Signing in again with the new address is both the simplest
     * correct thing and a useful confirmation that it works.
     */
    @PostMapping("/confirm")
    public String confirm(@RequestParam String code, @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest request, RedirectAttributes redirectAttributes) throws ServletException {
        User user = principal.getUser();
        if (!rateLimiter.tryConsume("email-change-confirm:" + user.getId(), 15, WINDOW)) {
            redirectAttributes.addFlashAttribute("error", "Too many attempts — please wait a few minutes.");
            return "redirect:/account/email";
        }
        if (!otpService.verifyEmailChange(user.getId(), code)) {
            redirectAttributes.addFlashAttribute("error", "That code is invalid or has expired.");
            return "redirect:/account/email";
        }
        try {
            userService.confirmEmailChange(user.getId());
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/account/email";
        }
        userSessionRegistry.revokeAllSessions(user.getEmail());
        request.logout();
        return "redirect:/login?emailChanged";
    }

    @PostMapping("/cancel")
    public String cancel(@AuthenticationPrincipal AppUserPrincipal principal,
            RedirectAttributes redirectAttributes) {
        userService.cancelEmailChange(principal.getUser().getId());
        redirectAttributes.addFlashAttribute("cancelled", true);
        return "redirect:/account/email";
    }

    private User refreshed(User user) {
        return userService.findById(user.getId()).orElse(user);
    }

    private void addContext(Model model, User user) {
        User current = refreshed(user);
        Role active = current.getActiveRole() != null ? current.getActiveRole() : current.getRole();
        model.addAttribute("consoleUser", active != Role.USER);
        model.addAttribute("currentEmail", current.getEmail());
        model.addAttribute("pendingEmail", current.getPendingEmail());
        model.addAttribute("pageTitle", "Change email");
    }
}
