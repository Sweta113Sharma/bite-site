package com.bitesite.controller.admin;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.PortalGuard;
import com.bitesite.dto.OutletForm;
import com.bitesite.dto.StaffForm;
import com.bitesite.dto.TenantForm;
import com.bitesite.exception.BusinessException;
import com.bitesite.exception.DuplicateEmailException;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.Outlet;
import com.bitesite.model.Role;
import com.bitesite.model.StaffScope;
import com.bitesite.model.User;
import com.bitesite.service.OutletService;
import com.bitesite.service.TenantService;
import com.bitesite.service.UserService;
import com.bitesite.tenant.Tenant;
import com.bitesite.tenant.TenantStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;
    private final OutletService outletService;
    private final UserService userService;

    @GetMapping
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        model.addAttribute("tenants", tenantService.listAll());
        model.addAttribute("statuses", TenantStatus.values());
        model.addAttribute("pageTitle", "Colleges");
        return "admin/tenants";
    }

    @GetMapping("/new")
    public String newForm(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new TenantForm());
        }
        model.addAttribute("pageTitle", "Onboard a college");
        return "admin/tenant-form";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") TenantForm form, BindingResult bindingResult, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        if (bindingResult.hasErrors()) {
            model.addAttribute("pageTitle", "Onboard a college");
            return "admin/tenant-form";
        }
        Tenant tenant = tenantService.create(form.getName(), principal.getUser().getId());
        return "redirect:/admin/tenants/" + tenant.getId();
    }

    @GetMapping("/{id}")
    public String detail(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        Tenant tenant = tenantService.get(id);
        model.addAttribute("tenant", tenant);
        model.addAttribute("outlets", outletService.listAll(id));
        model.addAttribute("staff", userService.findByTenantId(id));
        model.addAttribute("statuses", TenantStatus.values());
        if (!model.containsAttribute("outletForm")) {
            model.addAttribute("outletForm", new OutletForm());
        }
        if (!model.containsAttribute("staffForm")) {
            model.addAttribute("staffForm", new StaffForm());
        }
        model.addAttribute("pageTitle", tenant.getName());
        return "admin/tenant-detail";
    }

    /**
     * The 150 is the column width, checked here as well as in the browser: an over-long
     * name would otherwise reach MySQL and come back as a data-truncation 500 rather than
     * something the admin can read and act on.
     */
    @PostMapping("/{id}/rename")
    public String rename(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            @RequestParam String name, RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("tenantError", "A college needs a name.");
        } else if (trimmed.length() > 150) {
            redirectAttributes.addFlashAttribute("tenantError",
                    "That name is " + trimmed.length() + " characters; the limit is 150.");
        } else {
            try {
                tenantService.rename(id, trimmed, principal.getUser().getId());
                redirectAttributes.addFlashAttribute("tenantNotice", "Renamed to " + trimmed + ".");
            } catch (BusinessException e) {
                redirectAttributes.addFlashAttribute("tenantError", e.getMessage());
            }
        }
        return "redirect:/admin/tenants/" + id;
    }

    @PostMapping("/{id}/status")
    public String changeStatus(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            @RequestParam TenantStatus status) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        tenantService.setStatus(id, status, principal.getUser().getId());
        return "redirect:/admin/tenants/" + id;
    }

    /**
     * Permanent deletion, gated on the admin typing the college's name back — the same
     * shape as the canteen deletion below it, because it is the same kind of act one level
     * up and should not feel different.
     *
     * <p>The typed-name check runs here and not only in the browser: a prompt that exists
     * only in JavaScript protects nobody from a mis-aimed form post, and this endpoint
     * destroys a college's canteens, menus and staff accounts. Exact match after trimming
     * — a close-enough match is precisely what the prompt is there to stop, and with three
     * near-identically named colleges on screen it is the realistic mistake.
     */
    @PostMapping("/{id}/delete")
    public String delete(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            @RequestParam(required = false) String confirmName, RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        Tenant tenant = tenantService.get(id);
        if (confirmName == null || !confirmName.trim().equals(tenant.getName())) {
            redirectAttributes.addFlashAttribute("tenantError",
                    "Type the college's name exactly (\"" + tenant.getName() + "\") to confirm deletion.");
            return "redirect:/admin/tenants/" + id;
        }
        try {
            int staff = tenantService.delete(id, principal.getUser().getId());
            redirectAttributes.addFlashAttribute("notice", tenant.getName() + " and its canteens were deleted."
                    + (staff == 0 ? ""
                            : " " + staff + " staff account" + (staff == 1 ? " was" : "s were") + " removed."));
            return "redirect:/admin/tenants";
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("tenantError", e.getMessage());
            return "redirect:/admin/tenants/" + id;
        }
    }

    @PostMapping("/{id}/logo")
    public String uploadLogo(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            @RequestParam("logo") MultipartFile logo, RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        tenantService.uploadLogo(id, logo, principal.getUser().getId());
        redirectAttributes.addFlashAttribute("logoUploaded", true);
        return "redirect:/admin/tenants/" + id;
    }

    @PostMapping("/{id}/outlets")
    public String addOutlet(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            @Valid @ModelAttribute("outletForm") OutletForm form,
            BindingResult bindingResult, RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("org.springframework.validation.BindingResult.outletForm", bindingResult);
            redirectAttributes.addFlashAttribute("outletForm", form);
            return "redirect:/admin/tenants/" + id;
        }
        outletService.create(id, form.getName());
        return "redirect:/admin/tenants/" + id;
    }

    @PostMapping("/{id}/staff")
    public String addStaff(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            @Valid @ModelAttribute("staffForm") StaffForm form,
            BindingResult bindingResult, RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        // The role now comes from the form, so it has to be checked. Without this, a
        // hand-crafted post could mint a SUPER_ADMIN through the outlet-staff form — and a
        // tenant-scoped account holding a platform role is incoherent besides. Already
        // FULL_ADMIN-gated above; this is the second lock, not the first.
        if (form.getRole() != null && !form.getRole().isOutletPortalRole()) {
            bindingResult.rejectValue("role", "invalid", "Outlet staff must be a manager or an operator.");
        }
        if (!bindingResult.hasErrors()) {
            try {
                userService.createUser(id, form.getOutletId(), form.getName(), form.getEmail(), form.getPassword(),
                        form.getRole());
                return "redirect:/admin/tenants/" + id;
            } catch (DuplicateEmailException e) {
                bindingResult.rejectValue("email", "duplicate", e.getMessage());
            }
        }
        redirectAttributes.addFlashAttribute("org.springframework.validation.BindingResult.staffForm", bindingResult);
        redirectAttributes.addFlashAttribute("staffForm", form);
        return "redirect:/admin/tenants/" + id;
    }

    /**
     * Emails a reset code to an outlet account. This screen listed staff with no actions
     * at all, so a canteen whose manager had forgotten their password had nobody who could
     * help — the manager's own staff screen can reset their operators, but nothing could
     * reset the manager.
     */
    @PostMapping("/{id}/staff/{userId}/password-reset")
    public String sendStaffPasswordReset(@AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id, @PathVariable Long userId, RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        try {
            userService.sendTenantStaffPasswordReset(userId, id, principal.getUser().getId());
            redirectAttributes.addFlashAttribute("staffNotice",
                    "A reset code is on its way to their email. It expires in 10 minutes.");
        } catch (ResourceNotFoundException e) {
            // Extends BusinessException, so without this branch the one below swallows it
            // and a bad id renders as a notice instead of the 404 it is.
            throw e;
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("staffError", e.getMessage());
        }
        return "redirect:/admin/tenants/" + id;
    }

    @PostMapping("/{id}/outlets/{outletId}/rename")
    public String renameOutlet(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            @PathVariable Long outletId, @RequestParam String name, RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("outletError", "A canteen needs a name.");
        } else {
            outletService.rename(outletId, id, trimmed, principal.getUser().getId());
            redirectAttributes.addFlashAttribute("outletNotice", "Renamed to " + trimmed + ".");
        }
        return "redirect:/admin/tenants/" + id;
    }

    @PostMapping("/{id}/outlets/{outletId}/status")
    public String setOutletActive(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            @PathVariable Long outletId, @RequestParam boolean active, RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        outletService.setActive(outletId, id, active, principal.getUser().getId());
        redirectAttributes.addFlashAttribute("outletNotice", active
                ? "Canteen enabled — it's back in the student app."
                : "Canteen disabled — students can no longer see it or order from it.");
        return "redirect:/admin/tenants/" + id;
    }

    /**
     * Permanent deletion, gated on the admin typing the canteen's name back.
     *
     * <p>The check is repeated here rather than left to the confirmation box in the
     * browser: a typed-name prompt that only exists in JavaScript protects nobody from a
     * mis-aimed form post, and this endpoint destroys a canteen's whole menu. Comparison
     * is exact after trimming — a close-enough match is exactly what the prompt is there
     * to stop.
     */
    @PostMapping("/{id}/outlets/{outletId}/delete")
    public String deleteOutlet(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            @PathVariable Long outletId, @RequestParam(required = false) String confirmName,
            RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        Outlet outlet = outletService.get(outletId, id);
        if (confirmName == null || !confirmName.trim().equals(outlet.getName())) {
            redirectAttributes.addFlashAttribute("outletError",
                    "Type the canteen's name exactly (\"" + outlet.getName() + "\") to confirm deletion.");
            return "redirect:/admin/tenants/" + id;
        }
        try {
            int deactivatedStaff = outletService.delete(outletId, id, principal.getUser().getId());
            redirectAttributes.addFlashAttribute("outletNotice", outlet.getName() + " and its menu were deleted."
                    + (deactivatedStaff == 0 ? ""
                            : " " + deactivatedStaff + " staff account" + (deactivatedStaff == 1 ? " was" : "s were")
                                    + " switched off."));
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("outletError", e.getMessage());
        }
        return "redirect:/admin/tenants/" + id;
    }
}
