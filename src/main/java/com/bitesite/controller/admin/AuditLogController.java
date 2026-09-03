package com.bitesite.controller.admin;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.PortalGuard;
import com.bitesite.model.StaffScope;
import com.bitesite.dao.AuditLogDao;
import com.bitesite.dto.Paged;
import com.bitesite.model.AuditLogEntry;
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

    /** Rows per page. The audit log is the screen where "only the most recent hundred"
     * hurt most: it is consulted precisely when someone needs to know what happened a
     * while ago, and it truncated silently. */
    private static final int PAGE_SIZE = 50;

    @GetMapping
    public String list(@AuthenticationPrincipal AppUserPrincipal principal,
                       @RequestParam(required = false) Long tenantId,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        model.addAttribute("tenants", tenantDao.findAll());
        model.addAttribute("selectedTenantId", tenantId);
        int safePage = Math.max(0, page);
        if (tenantId != null) {
            Paged<AuditLogEntry> paged = Paged.of(
                    auditLogDao.findByTenantId(tenantId, PAGE_SIZE + 1,
                            Paged.offsetFor(safePage, PAGE_SIZE)),
                    safePage, PAGE_SIZE);
            model.addAttribute("paged", paged);
            model.addAttribute("entries", paged.items());
        } else {
            model.addAttribute("entries", List.of());
        }
        model.addAttribute("pageTitle", "Audit log");
        return "admin/audit-log";
    }
}
