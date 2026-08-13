package com.bitesite.controller.auth;

import com.bitesite.dto.StudentRegistrationForm;
import com.bitesite.exception.DuplicateEmailException;
import com.bitesite.service.UserService;
import com.bitesite.tenant.Tenant;
import com.bitesite.tenant.TenantDao;
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

import java.util.List;

@Controller
@RequestMapping("/register/student")
@RequiredArgsConstructor
public class RegistrationController {

    private final UserService userService;
    private final TenantDao tenantDao;

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
            Model model, RedirectAttributes redirectAttributes) {
        List<Tenant> activeColleges = tenantDao.findActive();

        if (!bindingResult.hasErrors()) {
            boolean validCollege = activeColleges.stream().anyMatch(t -> t.getId().equals(form.getTenantId()));
            if (!validCollege) {
                bindingResult.rejectValue("tenantId", "invalid", "Select a valid college");
            }
        }

        if (!bindingResult.hasErrors()) {
            try {
                userService.registerStudent(form.getTenantId(), form.getName(), form.getEmail(), form.getPassword(),
                        form.getPhone(), form.getRollNo());
                redirectAttributes.addFlashAttribute("registered", true);
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
