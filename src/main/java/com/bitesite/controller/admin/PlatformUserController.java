package com.bitesite.controller.admin;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.PortalGuard;
import com.bitesite.config.RoleAssignment;
import com.bitesite.dto.PlatformUserForm;
import com.bitesite.exception.BusinessException;
import com.bitesite.exception.DuplicateEmailException;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.Role;
import com.bitesite.model.StaffScope;
import com.bitesite.model.User;
import com.bitesite.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Platform-level accounts (tenant_id = NULL): SUPER_ADMIN and TECH_MANAGER. Creating one
 * and granting/revoking additional roles are both SUPER_ADMIN-only (FULL_ADMIN scope) —
 * this is exactly the kind of privilege escalation surface that shouldn't be reachable by
 * a TECH_MANAGER, even though TECH_MANAGER is otherwise an admin-portal role.
 */
@Controller
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class PlatformUserController {

    private final UserService userService;

    @GetMapping
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        List<User> users = userService.findPlatformUsers();
        model.addAttribute("users", users);
        addRoleAssignmentModel(model, principal.getUser(), users);
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new PlatformUserForm());
        }
        model.addAttribute("pageTitle", "Platform users");
        return "admin/users";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") PlatformUserForm form, BindingResult bindingResult, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        // An admin reaches this screen but cannot mint an elevated account from it. The
        // picker below already omits those roles; this is the lock, not the prompt.
        if (form.getRole() != null) {
            RoleAssignment.requireCanCreateWith(principal.getUser(), form.getRole());
        }
        if (!bindingResult.hasErrors()) {
            try {
                userService.createUser(null, null, form.getName(), form.getEmail(), form.getPassword(), form.getRole());
                return "redirect:/admin/users";
            } catch (DuplicateEmailException e) {
                bindingResult.rejectValue("email", "duplicate", e.getMessage());
            }
        }
        List<User> users = userService.findPlatformUsers();
        model.addAttribute("users", users);
        addRoleAssignmentModel(model, principal.getUser(), users);
        model.addAttribute("pageTitle", "Platform users");
        return "admin/users";
    }

    /**
     * Emails a reset code to a platform account. Sends to the account's own inbox rather
     * than setting a password here, so an admin never handles another admin's credential
     * — and so the new one is never spoken aloud, pasted into a chat, or left in a ticket.
     */
    @PostMapping("/{id}/password-reset")
    public String sendPasswordReset(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        try {
            userService.sendPlatformUserPasswordReset(id, principal.getUser().getId());
            redirectAttributes.addFlashAttribute("notice",
                    "A reset code is on its way to that account's email. It expires in 10 minutes.");
        } catch (ResourceNotFoundException e) {
            // ResourceNotFoundException extends BusinessException, so without this branch
            // the one below swallows it and a bad id renders as a friendly notice on this
            // page instead of the 404 every other lookup here produces.
            throw e;
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }

    /**
     * What the screen may offer this actor, and which rows they may touch at all. Handed
     * to the view so the controls that would 403 are simply not drawn — a button that
     * exists only to refuse you is worse than no button.
     */
    private void addRoleAssignmentModel(Model model, User actor, List<User> users) {
        model.addAttribute("assignableRoles", RoleAssignment.assignableBy(actor));
        model.addAttribute("manageable", users.stream().collect(Collectors.toMap(
                User::getId, u -> RoleAssignment.canManage(actor, u))));
        model.addAttribute("canAssignElevated", RoleAssignment.canAssign(actor, Role.SUPER_ADMIN));
    }

    @PostMapping("/{id}/roles/grant")
    public String grantRole(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            @RequestParam Role role) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        userService.grantRole(id, role, principal.getUser().getId());
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/roles/revoke")
    public String revokeRole(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            @RequestParam Role role, RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        try {
            userService.revokeRole(id, role, principal.getUser().getId());
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }
}
