package com.bitesite.controller.techmgr;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.service.TechConfigService;
import com.bitesite.tenant.TenantDao;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/techmgr/tenants")
@RequiredArgsConstructor
public class TechConfigController {

    private final TechConfigService techConfigService;
    private final TenantDao tenantDao;

    @GetMapping("/{id}")
    public String edit(@PathVariable Long id, Model model) {
        model.addAttribute("tenant", tenantDao.findById(id).orElseThrow());
        model.addAttribute("entries", techConfigService.listForTenant(id));
        model.addAttribute("pageTitle", "Configuration");
        return "techmgr/tenant-config";
    }

    @PostMapping("/{id}/config")
    public String set(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id,
            @RequestParam String key, @RequestParam String value) {
        techConfigService.set(id, key, value, principal.getUser().getId());
        return "redirect:/techmgr/tenants/" + id;
    }
}
