package com.bitesite.controller.admin;

import com.bitesite.dao.AuditLogDao;
import com.bitesite.tenant.TenantDao;
import lombok.RequiredArgsConstructor;
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
    public String list(@RequestParam(required = false) Long tenantId, Model model) {
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
