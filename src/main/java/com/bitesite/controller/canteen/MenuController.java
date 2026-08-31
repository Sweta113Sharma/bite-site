package com.bitesite.controller.canteen;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.PortalGuard;
import com.bitesite.dto.MenuItemForm;
import com.bitesite.model.MenuItem;
import com.bitesite.model.StaffScope;
import com.bitesite.model.User;
import com.bitesite.service.CategoryService;
import com.bitesite.service.MenuService;
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
@RequestMapping("/canteen/menu")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;
    private final CategoryService categoryService;

    /** Both roles: an operator needs this list to reach the stock toggles below. */
    @GetMapping
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_OPS);
        User user = principal.getUser();
        model.addAttribute("items", menuService.listForOutlet(user.getOutletId(), user.getTenantId()));
        model.addAttribute("pageTitle", "Menu");
        return "canteen/menu";
    }

    // Guarded on the GET as well as the POST it submits to. Showing an operator a form
    // that 403s on submit is a worse experience than not showing it.
    @GetMapping("/new")
    public String newForm(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_MANAGE);
        User user = principal.getUser();
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new MenuItemForm());
        }
        model.addAttribute("categories", categoryService.listForOutlet(user.getOutletId(), user.getTenantId()));
        model.addAttribute("pageTitle", "Add menu item");
        return "canteen/menu-form";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") MenuItemForm form, BindingResult bindingResult,
            @RequestParam(value = "photo", required = false) MultipartFile photo, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_MANAGE);
        User user = principal.getUser();
        if (bindingResult.hasErrors()) {
            model.addAttribute("categories", categoryService.listForOutlet(user.getOutletId(), user.getTenantId()));
            model.addAttribute("pageTitle", "Add menu item");
            return "canteen/menu-form";
        }
        menuService.create(user.getTenantId(), user.getOutletId(), form, photo, user.getId());
        return "redirect:/canteen/menu";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_MANAGE);
        User user = principal.getUser();
        MenuItem item = menuService.getWithTodayCount(id, user.getTenantId());
        MenuItemForm form = new MenuItemForm();
        form.setName(item.getName());
        form.setCategoryId(item.getCategoryId());
        form.setPrice(item.getPrice());
        form.setDiscountPrice(item.getDiscountPrice());
        form.setDiscountPercent(item.getDiscountPercent());
        form.setDailyLimit(item.getDailyLimit());
        model.addAttribute("form", form);
        model.addAttribute("itemId", id);
        model.addAttribute("currentPhotoPath", item.getPhotoPath());
        model.addAttribute("soldToday", item.getSoldToday());
        model.addAttribute("categories", categoryService.listForOutlet(user.getOutletId(), user.getTenantId()));
        model.addAttribute("pageTitle", "Edit menu item");
        return "canteen/menu-form";
    }

    @PostMapping("/{id}")
    public String update(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            @Valid @ModelAttribute("form") MenuItemForm form, BindingResult bindingResult,
            @RequestParam(value = "photo", required = false) MultipartFile photo, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_MANAGE);
        User user = principal.getUser();
        if (bindingResult.hasErrors()) {
            MenuItem existing = menuService.getWithTodayCount(id, user.getTenantId());
            model.addAttribute("itemId", id);
            model.addAttribute("currentPhotoPath", existing.getPhotoPath());
            model.addAttribute("soldToday", existing.getSoldToday());
            model.addAttribute("categories", categoryService.listForOutlet(user.getOutletId(), user.getTenantId()));
            model.addAttribute("pageTitle", "Edit menu item");
            return "canteen/menu-form";
        }
        menuService.update(id, user.getTenantId(), form, photo, user.getId());
        return "redirect:/canteen/menu";
    }

    /**
     * Shared with operators, deliberately. An operator can already pause the entire
     * outlet, so per-item availability is a strictly gentler version of a power they
     * hold — while withholding it leaves someone who has just run out of paneer choosing
     * between closing the whole canteen and cancelling orders one by one. This marks
     * today's state; a manager who wants an item gone for good uses delete.
     */
    @PostMapping("/{id}/toggle")
    public String toggleAvailability(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_OPS);
        User user = principal.getUser();
        MenuItem item = menuService.get(id, user.getTenantId());
        menuService.setAvailability(id, user.getTenantId(), !item.isAvailable(), user.getId());
        return "redirect:/canteen/menu";
    }

    /**
     * Opening-time reset: everything marked out of stock yesterday goes back on sale.
     *
     * <p>Shared, and it must match the toggle above — an operator who could switch items
     * off but not back on would strand a mis-click until a manager came in.
     */
    @PostMapping("/restock-all")
    public String restockAll(@AuthenticationPrincipal AppUserPrincipal principal,
            RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_OPS);
        User user = principal.getUser();
        int restored = menuService.markAllAvailable(user.getOutletId(), user.getTenantId(), user.getId());
        redirectAttributes.addFlashAttribute("menuNotice", restored == 0
                ? "Everything was already marked available."
                : restored + (restored == 1 ? " item is" : " items are") + " back on sale.");
        return "redirect:/canteen/menu";
    }

    @PostMapping("/{id}/delete")
    public String delete(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_MANAGE);
        User user = principal.getUser();
        menuService.delete(id, user.getTenantId(), user.getId());
        return "redirect:/canteen/menu";
    }
}
