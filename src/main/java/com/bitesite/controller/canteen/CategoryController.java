package com.bitesite.controller.canteen;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.PortalGuard;
import com.bitesite.exception.BusinessException;
import com.bitesite.model.StaffScope;
import com.bitesite.model.User;
import com.bitesite.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Menu sections. Manager-only: this is the menu's structure, not today's stock. */
@Controller
@RequestMapping("/canteen/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_MANAGE);
        User user = principal.getUser();
        model.addAttribute("categories", categoryService.listForOutlet(user.getOutletId(), user.getTenantId()));
        model.addAttribute("pageTitle", "Categories");
        return "canteen/categories";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam String name, RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_MANAGE);
        User user = principal.getUser();
        try {
            categoryService.create(user.getTenantId(), user.getOutletId(), name, user.getId());
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("categoryError", e.getMessage());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // uq_categories_outlet_name. Reported plainly rather than as a 500 — trying to
            // add a section that already exists is an ordinary mistake, not a fault.
            redirectAttributes.addFlashAttribute("categoryError",
                    "There's already a category called \"" + name.trim() + "\".");
        }
        return "redirect:/canteen/categories";
    }

    @PostMapping("/{id}/rename")
    public String rename(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            @RequestParam String name, RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_MANAGE);
        User user = principal.getUser();
        try {
            categoryService.rename(id, user.getTenantId(), name, user.getId());
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("categoryError", e.getMessage());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            redirectAttributes.addFlashAttribute("categoryError",
                    "There's already a category called \"" + name.trim() + "\".");
        }
        return "redirect:/canteen/categories";
    }

    @PostMapping("/{id}/order")
    public String reorder(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            @RequestParam int sortOrder) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_MANAGE);
        User user = principal.getUser();
        categoryService.reorder(id, user.getTenantId(), sortOrder, user.getId());
        return "redirect:/canteen/categories";
    }

    @PostMapping("/{id}/delete")
    public String delete(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_MANAGE);
        User user = principal.getUser();
        try {
            categoryService.delete(id, user.getTenantId(), user.getId());
            redirectAttributes.addFlashAttribute("categoryNotice", "Category deleted.");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("categoryError", e.getMessage());
        }
        return "redirect:/canteen/categories";
    }
}
