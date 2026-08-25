package com.bitesite.controller.canteen;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.dto.MenuItemForm;
import com.bitesite.model.MenuItem;
import com.bitesite.model.User;
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

    @GetMapping
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        User user = principal.getUser();
        model.addAttribute("items", menuService.listForOutlet(user.getOutletId(), user.getTenantId()));
        model.addAttribute("pageTitle", "Menu");
        return "canteen/menu";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new MenuItemForm());
        }
        model.addAttribute("pageTitle", "Add menu item");
        return "canteen/menu-form";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") MenuItemForm form, BindingResult bindingResult,
            @RequestParam(value = "photo", required = false) MultipartFile photo, Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("pageTitle", "Add menu item");
            return "canteen/menu-form";
        }
        User user = principal.getUser();
        menuService.create(user.getTenantId(), user.getOutletId(), form, photo, user.getId());
        return "redirect:/canteen/menu";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id, Model model) {
        User user = principal.getUser();
        MenuItem item = menuService.getWithTodayCount(id, user.getTenantId());
        MenuItemForm form = new MenuItemForm();
        form.setName(item.getName());
        form.setCategory(item.getCategory());
        form.setPrice(item.getPrice());
        form.setDiscountPrice(item.getDiscountPrice());
        form.setDiscountPercent(item.getDiscountPercent());
        form.setDailyLimit(item.getDailyLimit());
        model.addAttribute("form", form);
        model.addAttribute("itemId", id);
        model.addAttribute("currentPhotoPath", item.getPhotoPath());
        model.addAttribute("soldToday", item.getSoldToday());
        model.addAttribute("pageTitle", "Edit menu item");
        return "canteen/menu-form";
    }

    @PostMapping("/{id}")
    public String update(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            @Valid @ModelAttribute("form") MenuItemForm form, BindingResult bindingResult,
            @RequestParam(value = "photo", required = false) MultipartFile photo, Model model) {
        User user = principal.getUser();
        if (bindingResult.hasErrors()) {
            MenuItem existing = menuService.getWithTodayCount(id, user.getTenantId());
            model.addAttribute("itemId", id);
            model.addAttribute("currentPhotoPath", existing.getPhotoPath());
            model.addAttribute("soldToday", existing.getSoldToday());
            model.addAttribute("pageTitle", "Edit menu item");
            return "canteen/menu-form";
        }
        menuService.update(id, user.getTenantId(), form, photo, user.getId());
        return "redirect:/canteen/menu";
    }

    @PostMapping("/{id}/toggle")
    public String toggleAvailability(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id) {
        User user = principal.getUser();
        MenuItem item = menuService.get(id, user.getTenantId());
        menuService.setAvailability(id, user.getTenantId(), !item.isAvailable(), user.getId());
        return "redirect:/canteen/menu";
    }

    /** Opening-time reset: everything marked out of stock yesterday goes back on sale. */
    @PostMapping("/restock-all")
    public String restockAll(@AuthenticationPrincipal AppUserPrincipal principal,
            RedirectAttributes redirectAttributes) {
        User user = principal.getUser();
        int restored = menuService.markAllAvailable(user.getOutletId(), user.getTenantId(), user.getId());
        redirectAttributes.addFlashAttribute("menuNotice", restored == 0
                ? "Everything was already marked available."
                : restored + (restored == 1 ? " item is" : " items are") + " back on sale.");
        return "redirect:/canteen/menu";
    }

    @PostMapping("/{id}/delete")
    public String delete(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id) {
        User user = principal.getUser();
        menuService.delete(id, user.getTenantId(), user.getId());
        return "redirect:/canteen/menu";
    }
}
