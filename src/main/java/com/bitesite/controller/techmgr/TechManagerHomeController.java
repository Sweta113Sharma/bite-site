package com.bitesite.controller.techmgr;

import com.bitesite.tenant.TenantDao;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class TechManagerHomeController {

    private final TenantDao tenantDao;

    @GetMapping("/techmgr")
    public String home(Model model) {
        model.addAttribute("tenants", tenantDao.findAll());
        model.addAttribute("pageTitle", "Tech Manager");
        return "techmgr/dashboard";
    }
}
