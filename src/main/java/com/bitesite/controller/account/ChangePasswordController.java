package com.bitesite.controller.account;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.UserSessionRegistry;
import com.bitesite.dto.ChangePasswordForm;
import com.bitesite.exception.BusinessException;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import com.bitesite.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Changing your own password, for anyone signed in.
 *
 * <p>Deliberately not under {@code /student}, {@code /canteen} or {@code /admin}: every
 * account has a password, so a route that lived on one portal would leave the other three
 * without one. That is how it stood before this — no account of any role could change its
 * password through the product at all, so rotating a credential meant an UPDATE against
 * the database. {@code /account/**} is bypassed by the portal gate for the same reason
 * role-switching is: it has to work from whichever portal you happen to be on.
 */
@Controller
@RequestMapping("/account/password")
@RequiredArgsConstructor
public class ChangePasswordController {

    private final UserService userService;
    private final SecurityContextRepository securityContextRepository;
    private final UserSessionRegistry userSessionRegistry;

    @GetMapping
    public String show(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new ChangePasswordForm());
        }
        model.addAttribute("consoleUser", isConsoleUser(principal.getUser()));
        model.addAttribute("pageTitle", "Change password");
        return "account/change-password";
    }

    @PostMapping
    public String change(@Valid @ModelAttribute("form") ChangePasswordForm form, BindingResult bindingResult,
            @AuthenticationPrincipal AppUserPrincipal principal, HttpServletRequest request,
            HttpServletResponse response, Model model, RedirectAttributes redirectAttributes) {
        User user = principal.getUser();

        if (!bindingResult.hasErrors() && !form.confirmationMatches()) {
            bindingResult.rejectValue("confirmPassword", "mismatch", "Those passwords don't match");
        }
        // Caught here as well as in the service so the message lands under the field it is
        // about. The service's own check is the authoritative one and stays: it compares
        // against the stored hash, and guards callers that never pass through this form.
        if (!bindingResult.hasErrors() && form.getNewPassword().equals(form.getCurrentPassword())) {
            bindingResult.rejectValue("newPassword", "unchanged",
                    "Your new password must be different from your current one");
        }
        if (!bindingResult.hasErrors()) {
            try {
                userService.changeOwnPassword(user.getId(), form.getCurrentPassword(), form.getNewPassword());
            } catch (BusinessException e) {
                // What is left is a wrong current password, which is safe to say plainly:
                // the caller is already authenticated as this account, so it tells them
                // nothing they could not already establish.
                bindingResult.rejectValue("currentPassword", "invalid", e.getMessage());
            }
        }
        if (bindingResult.hasErrors()) {
            model.addAttribute("consoleUser", isConsoleUser(user));
            model.addAttribute("pageTitle", "Change password");
            return "account/change-password";
        }

        // Anyone else holding a session on this account is using the password that was
        // just replaced. This one stays alive — the person who just typed the old password
        // correctly should not be thrown back to the login screen for doing the right thing.
        HttpSession session = request.getSession(false);
        userSessionRegistry.revokeOtherSessions(user.getEmail(), session != null ? session.getId() : null);
        refreshPrincipal(user.getId(), request, response);

        redirectAttributes.addFlashAttribute("passwordChanged", true);
        return "redirect:/account/password";
    }

    /**
     * Replaces the session's principal with one re-read from the database.
     *
     * <p>{@link AppUserPrincipal} wraps a snapshot of the user row, including the password
     * hash, so after a change the session is holding the hash that no longer exists. It
     * costs nothing per request today, but leaving a stale credential in the session is
     * the kind of thing a later {@code matches()} against the principal gets wrong.
     * Saving to the repository explicitly is what makes it stick past this request — see
     * the same reasoning in {@code RoleSwitchController}.
     */
    private void refreshPrincipal(Long userId, HttpServletRequest request, HttpServletResponse response) {
        userService.findById(userId).ifPresent(refreshed -> {
            AppUserPrincipal principal = new AppUserPrincipal(refreshed);
            Authentication auth = new UsernamePasswordAuthenticationToken(
                    principal, principal.getPassword(), principal.getAuthorities());
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
        });
    }

    /** Staff and admin screens carry {@code body.console}, which is what switches the page
     * onto the console design. One shared template serves every role, so it has to ask. */
    private boolean isConsoleUser(User user) {
        Role active = user.getActiveRole() != null ? user.getActiveRole() : user.getRole();
        return active != Role.USER;
    }
}
