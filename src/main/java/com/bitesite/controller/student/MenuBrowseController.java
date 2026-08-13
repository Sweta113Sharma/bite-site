package com.bitesite.controller.student;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.MenuItem;
import com.bitesite.model.Outlet;
import com.bitesite.model.User;
import com.bitesite.service.MenuService;
import com.bitesite.service.OutletService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/student/menu")
@RequiredArgsConstructor
public class MenuBrowseController {

    private final MenuService menuService;
    private final OutletService outletService;

    @GetMapping
    public String browse(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) Long outletId, Model model) {
        User user = principal.getUser();
        List<Outlet> outlets = outletService.listActive(user.getTenantId());
        if (outlets.isEmpty()) {
            model.addAttribute("pageTitle", "Menu");
            model.addAttribute("outlets", outlets);
            return "student/menu";
        }

        Outlet selected = outletId == null
                ? outlets.get(0)
                : outlets.stream().filter(o -> o.getId().equals(outletId)).findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException("Outlet not found"));

        List<MenuItem> items = menuService.listAvailableForOutlet(selected.getId(), user.getTenantId());
        Map<String, List<MenuItem>> byCategory = items.stream()
                .collect(Collectors.groupingBy(MenuItem::getCategory, LinkedHashMap::new, Collectors.toList()));

        model.addAttribute("outlets", outlets);
        model.addAttribute("selectedOutlet", selected);
        model.addAttribute("itemsByCategory", byCategory);
        model.addAttribute("pageTitle", "Menu");
        return "student/menu";
    }
}
