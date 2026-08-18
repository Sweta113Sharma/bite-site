package com.bitesite.controller.auth;

import com.bitesite.config.RateLimiter;
import com.bitesite.dto.StudentRegistrationForm;
import com.bitesite.exception.DuplicateEmailException;
import com.bitesite.model.User;
import com.bitesite.service.OtpService;
import com.bitesite.service.UserService;
import com.bitesite.tenant.Tenant;
import com.bitesite.tenant.TenantDao;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.util.List;

@Controller
@RequestMapping("/register/student")
@RequiredArgsConstructor
public class RegistrationController {

    // IP-keyed like login — generous enough that a whole college behind one campus NAT
    // registering during orientation week doesn't get blocked, tight enough to stop
    // automated bulk account creation.
    private static final int MAX_ATTEMPTS = 20;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final UserService userService;
    private final TenantDao tenantDao;
    private final OtpService otpService;
    private final RateLimiter rateLimiter;

    @GetMapping
    public String showForm(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new StudentRegistrationForm());
        }
        model.addAttribute("colleges", tenantDao.findActive());
        model.addAttribute("pageTitle", "Create account");
        return "auth/register";
    }

    @PostMapping
    public String register(@Valid @ModelAttribute("form") StudentRegistrationForm form, BindingResult bindingResult,
            Model model, RedirectAttributes redirectAttributes, HttpServletRequest request, HttpSession session) {
        List<Tenant> activeColleges = tenantDao.findActive();

        if (!rateLimiter.tryConsume("register:" + request.getRemoteAddr(), MAX_ATTEMPTS, WINDOW)) {
            model.addAttribute("rateLimited", true);
            model.addAttribute("colleges", activeColleges);
            model.addAttribute("pageTitle", "Create account");
            return "auth/register";
        }

        if (!bindingResult.hasErrors()) {
            boolean validCollege = activeColleges.stream().anyMatch(t -> t.getId().equals(form.getTenantId()));
            if (!validCollege) {
                bindingResult.rejectValue("tenantId", "invalid", "Select a valid college");
            }
        }

        if (!bindingResult.hasErrors()) {
            try {
                User saved = userService.registerStudent(form.getTenantId(), form.getName(), form.getEmail(),
                        form.getPassword(), form.getPhone(), form.getRollNo());
                if (!saved.isEmailVerified() || !saved.isPhoneVerified()) {
                    otpService.issueEmailOtp(saved);
                    otpService.issuePhoneOtp(saved);
                    session.setAttribute(VerificationController.PENDING_USER_ID, saved.getId());
                    return "redirect:/verify";
                }
                redirectAttributes.addFlashAttribute("registered", true);
                redirectAttributes.addFlashAttribute("verificationRequired", false);
                return "redirect:/login";
            } catch (DuplicateEmailException e) {
                bindingResult.rejectValue("email", "duplicate", e.getMessage());
            }
        }
        model.addAttribute("colleges", activeColleges);
        model.addAttribute("pageTitle", "Create account");
        return "auth/register";
    }
}
