package com.bitesite.controller.admin;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.PortalGuard;
import com.bitesite.config.RoleAssignment;
import com.bitesite.dto.Paged;
import com.bitesite.exception.BusinessException;
import com.bitesite.model.Outlet;
import com.bitesite.model.Role;
import com.bitesite.model.StaffScope;
import com.bitesite.model.User;
import com.bitesite.dao.OutletDao;
import com.bitesite.service.TenantService;
import com.bitesite.service.UserService;
import com.bitesite.tenant.Tenant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Every account on the platform, in one place.
 *
 * <p>Nothing answered "who is on this platform" before. Platform users lists only the
 * tenant-less accounts, outlet staff are visible a college at a time on that college's
 * page, and students were not listed anywhere at all — so a support question about a
 * named person had nowhere to begin.
 *
 * <p>Paged and filtered on the server, unlike the console's other lists which filter in
 * the browser. Those render one college's canteens or a handful of admins; this one
 * renders a student body, and the whole of it must never be in a page.
 *
 * <p>Acting on an account goes through {@link RoleAssignment}, so this screen cannot be
 * used to reach past the rule that governs /admin/users — a directory that touched every
 * account without that check would be a way around it.
 */
@Controller
@RequestMapping("/admin/accounts")
@RequiredArgsConstructor
public class AccountDirectoryController {

    /** Enough to scan, small enough to render on a phone on campus data. */
    private static final int PAGE_SIZE = 25;

    /** The value the college filter uses for "not attached to any college". */
    private static final String PLATFORM = "platform";

    private final UserService userService;
    private final TenantService tenantService;
    // Injected directly, the way PlatformOversightController does for the same listing.
    private final OutletDao outletDao;

    @GetMapping
    public String list(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) String college,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);

        // "" means every college, "platform" means the accounts with none, anything else
        // is an id. Translated here so the service never has to carry a magic number.
        boolean platformOnly = PLATFORM.equals(college);
        Long tenantId = null;
        if (college != null && !college.isBlank() && !platformOnly) {
            try {
                tenantId = Long.valueOf(college);
            } catch (NumberFormatException e) {
                // A hand-edited querystring narrows nothing rather than 500ing.
                tenantId = null;
            }
        }

        Paged<User> paged = userService.searchAccounts(q, role, tenantId, platformOnly, active,
                Math.max(0, page), PAGE_SIZE);

        model.addAttribute("paged", paged);
        model.addAttribute("accounts", paged.items());
        model.addAttribute("tenants", tenantService.listAll());
        model.addAttribute("roles", Role.values());
        model.addAttribute("q", q);
        model.addAttribute("selectedRole", role);
        model.addAttribute("selectedCollege", college);
        model.addAttribute("selectedActive", active);

        // Names rather than ids in the table: an admin reading this screen knows their
        // colleges by name and has never seen the numbers.
        model.addAttribute("collegeNames", tenantService.listAll().stream()
                .collect(Collectors.toMap(Tenant::getId, Tenant::getName, (a, b) -> a, LinkedHashMap::new)));
        model.addAttribute("outletNames", outletDao.findAllAcrossTenants().stream()
                .collect(Collectors.toMap(Outlet::getId, Outlet::getName, (a, b) -> a, LinkedHashMap::new)));

        // So the screen only draws controls this actor may actually use.
        Map<Long, Boolean> manageable = paged.items().stream()
                .collect(Collectors.toMap(User::getId,
                        u -> RoleAssignment.canManage(principal.getUser(), u), (a, b) -> a));
        model.addAttribute("manageable", manageable);
        // The filters this page is showing, so acting on a row can come back to it.
        // Built here because Thymeleaf 3.1 removed #request, so a template cannot read
        // its own querystring.
        model.addAttribute("backQuery", backQuery(q, role, college, active, paged.page()));
        model.addAttribute("selfId", principal.getUser().getId());
        model.addAttribute("pageTitle", "All accounts");
        return "admin/accounts";
    }

    private static String backQuery(String q, Role role, String college, Boolean active, int page) {
        StringBuilder sb = new StringBuilder();
        if (q != null && !q.isBlank()) {
            sb.append("&q=").append(URLEncoder.encode(q, StandardCharsets.UTF_8));
        }
        if (role != null) {
            sb.append("&role=").append(role.name());
        }
        if (college != null && !college.isBlank()) {
            sb.append("&college=").append(URLEncoder.encode(college, StandardCharsets.UTF_8));
        }
        if (active != null) {
            sb.append("&active=").append(active);
        }
        if (page > 0) {
            sb.append("&page=").append(page);
        }
        return sb.isEmpty() ? "" : sb.substring(1);
    }

    @PostMapping("/{userId}/status")
    public String setActive(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long userId,
            @RequestParam boolean active, @RequestParam(required = false) String back,
            RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.FULL_ADMIN);
        try {
            userService.setAccountActive(userId, active, principal.getUser().getId());
            redirectAttributes.addFlashAttribute("notice", active
                    ? "Account switched back on — they can sign in again."
                    : "Account switched off — they can no longer sign in.");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        // Returns to the filtered page they acted from, rather than dumping them at the
        // top of an unfiltered directory after every click.
        return "redirect:/admin/accounts" + (back == null || back.isBlank() ? "" : "?" + back);
    }
}
