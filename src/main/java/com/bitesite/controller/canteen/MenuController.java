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
            @Valid @ModelAttribute("form") MenuItemForm form, BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("pageTitle", "Add menu item");
            return "canteen/menu-form";
        }
        User user = principal.getUser();
        menuService.create(user.getTenantId(), user.getOutletId(), form.getName(), form.getCategory(),
                form.getPrice(), form.getDiscountPrice(), form.getDiscountPercent(), user.getId());
        return "redirect:/canteen/menu";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id, Model model) {
        User user = principal.getUser();
        MenuItem item = menuService.get(id, user.getTenantId());
        MenuItemForm form = new MenuItemForm();
        form.setName(item.getName());
        form.setCategory(item.getCategory());
        form.setPrice(item.getPrice());
        form.setDiscountPrice(item.getDiscountPrice());
        form.setDiscountPercent(item.getDiscountPercent());
        model.addAttribute("form", form);
        model.addAttribute("itemId", id);
        model.addAttribute("pageTitle", "Edit menu item");
        return "canteen/menu-form";
    }

    @PostMapping("/{id}")
    public String update(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            @Valid @ModelAttribute("form") MenuItemForm form, BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("itemId", id);
            model.addAttribute("pageTitle", "Edit menu item");
            return "canteen/menu-form";
        }
        User user = principal.getUser();
        menuService.update(id, user.getTenantId(), form.getName(), form.getCategory(), form.getPrice(),
                form.getDiscountPrice(), form.getDiscountPercent(), user.getId());
        return "redirect:/canteen/menu";
    }

    @PostMapping("/{id}/toggle")
    public String toggleAvailability(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id) {
        User user = principal.getUser();
        MenuItem item = menuService.get(id, user.getTenantId());
        menuService.setAvailability(id, user.getTenantId(), !item.isAvailable(), user.getId());
        return "redirect:/canteen/menu";
    }

    @PostMapping("/{id}/delete")
    public String delete(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id) {
        User user = principal.getUser();
        menuService.delete(id, user.getTenantId(), user.getId());
        return "redirect:/canteen/menu";
    }
}
