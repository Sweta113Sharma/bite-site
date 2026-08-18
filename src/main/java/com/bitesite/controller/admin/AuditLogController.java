package com.bitesite.controller.admin;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.PortalGuard;
import com.bitesite.model.StaffScope;
import com.bitesite.dao.AuditLogDao;
import com.bitesite.tenant.TenantDao;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
@RequestMapping("/admin/audit-log")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogDao auditLogDao;
    private final TenantDao tenantDao;

    @GetMapping
    public String list(@AuthenticationPrincipal AppUserPrincipal principal,
                       @RequestParam(required = false) Long tenantId, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        model.addAttribute("tenants", tenantDao.findAll());
        model.addAttribute("selectedTenantId", tenantId);
        if (tenantId != null) {
            model.addAttribute("entries", auditLogDao.findByTenantId(tenantId, 100));
        } else {
            model.addAttribute("entries", List.of());
        }
        model.addAttribute("pageTitle", "Audit log");
        return "admin/audit-log";
    }
}
