package com.bitesite.controller.account;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.controller.auth.VerificationController;
import com.bitesite.dto.ProfileForm;
import com.bitesite.exception.BusinessException;
import com.bitesite.service.Cart;
import com.bitesite.tenant.TenantDao;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import com.bitesite.service.OtpService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Editing the details you gave at signup.
 *
 * <p>Until now these were write-once: a name typed wrong during orientation week, or a
 * phone number that changed between terms, could only be corrected in the database. The
 * roll number matters more than it looks — it is what a canteen matches a student against
 * when an order is collected in person.
 *
 * <p>Email is deliberately absent. It is the login identifier and carries a uniqueness
 * constraint, so changing it needs its own verify-before-swap flow rather than a text box
 * next to the others.
 */
@Controller
@RequestMapping("/account/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final UserService userService;
    private final OtpService otpService;
    private final SecurityContextRepository securityContextRepository;
    private final TenantDao tenantDao;
    private final Cart cart;

    @GetMapping
    public String show(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        User user = principal.getUser();
        if (!model.containsAttribute("form")) {
            ProfileForm form = new ProfileForm();
            form.setName(user.getName());
            form.setPhone(user.getPhone());
            form.setRollNo(user.getRollNo());
            model.addAttribute("form", form);
        }
        addPageContext(model, user);
        return "account/profile";
    }

    @PostMapping
    public String update(@Valid @ModelAttribute("form") ProfileForm form, BindingResult bindingResult,
            @AuthenticationPrincipal AppUserPrincipal principal, HttpServletRequest request,
            HttpServletResponse response, HttpSession session, Model model,
            RedirectAttributes redirectAttributes) {
        User user = principal.getUser();
        if (bindingResult.hasErrors()) {
            addPageContext(model, user);
            return "account/profile";
        }

        boolean needsPhoneVerification = userService.updateOwnProfile(
                user.getId(), form.getName(), form.getPhone(), form.getRollNo());
        // The session's principal is a snapshot of the row, so without this the navbar
        // keeps greeting them by the old name until they sign in again.
        refreshPrincipal(user.getId(), request, response);

        if (needsPhoneVerification) {
            // A number that has not been proved would otherwise fail
            // AppUserPrincipal#isAccountNonLocked at their *next* sign-in, long after they
            // have forgotten they changed it. Send them through the OTP screen now, while
            // they still have the phone in their hand.
            userService.findById(user.getId()).ifPresent(refreshed -> {
                session.setAttribute(VerificationController.PENDING_USER_ID, refreshed.getId());
                otpService.issuePhoneOtp(refreshed);
            });
            return "redirect:/verify";
        }

        redirectAttributes.addFlashAttribute("profileSaved", true);
        return "redirect:/account/profile";
    }

    /**
     * Correcting the college chosen at signup.
     *
     * <p>A separate form and a separate post from the rest of the profile on purpose. The
     * other fields are a text box each and a mistake is a typo; this one moves the account
     * to a different college's menus and empties the cart, and it should not be possible to
     * do it by accident while fixing a phone number.
     */
    @PostMapping("/college")
    public String changeCollege(@RequestParam("tenantId") Long tenantId,
            @AuthenticationPrincipal AppUserPrincipal principal, HttpServletRequest request,
            HttpServletResponse response, HttpSession session, RedirectAttributes redirectAttributes) {
        User user = principal.getUser();
        try {
            // This session is spared; every other device signed in on this account is not,
            // because each of those holds a principal still pointing at the old college.
            userService.changeOwnCollege(user.getId(), tenantId, session.getId());
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("collegeError", e.getMessage());
            return "redirect:/account/profile";
        }
        // Items and the chosen outlet both belong to the college being left; an outlet id
        // from there means nothing at the new one. Cart#ensureOutlet would clear it at the
        // next menu load anyway, but leaving a stale basket on the bottom-nav badge in the
        // meantime looks like a bug.
        cart.clear();
        // The session's principal is the snapshot taken at sign-in, so without this the
        // student stays scoped to the college they just left for the rest of it.
        refreshPrincipal(user.getId(), request, response);
        redirectAttributes.addFlashAttribute("collegeChanged", true);
        return "redirect:/account/profile";
    }

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

    private void addPageContext(Model model, User user) {
        Role active = user.getActiveRole() != null ? user.getActiveRole() : user.getRole();
        model.addAttribute("consoleUser", active != Role.USER);
        // Roll number is a student field; showing it to a canteen manager is just noise.
        model.addAttribute("showRollNo", active == Role.USER);
        model.addAttribute("email", user.getEmail());
        // Only students have a college to correct, and only until their first order.
        if (active == Role.USER) {
            model.addAttribute("colleges", tenantDao.findActive());
            model.addAttribute("currentCollege", user.getTenantId() == null ? null
                    : tenantDao.findById(user.getTenantId()).orElse(null));
            model.addAttribute("canChangeCollege", userService.canChangeOwnCollege(user.getId()));
        }
        model.addAttribute("pageTitle", "Your profile");
    }
}
