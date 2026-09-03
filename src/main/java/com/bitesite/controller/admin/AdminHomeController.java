package com.bitesite.controller.admin;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.PortalGuard;
import com.bitesite.dao.DashboardDao;
import com.bitesite.model.StaffScope;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The admin home screen.
 *
 * <p>This route used to be a one-line redirect to the college list, so signing in as a
 * super admin put you in front of a table of colleges with no indication of whether
 * anything needed doing. Every number below already existed in the database and none of it
 * was ever surfaced: you found out about an unresolved grievance or a queue of failed
 * payments by visiting the screen and noticing.
 *
 * <p>Scoped to {@code OPS_SCOPE} rather than {@code FULL_ADMIN} — a tech manager is on call
 * for exactly the operational numbers this shows.
 */
@Controller
@RequiredArgsConstructor
public class AdminHomeController {

    private final DashboardDao dashboardDao;

    @GetMapping("/admin")
    public String home(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OPS_SCOPE);
        model.addAttribute("snapshot", dashboardDao.platformSnapshot());
        model.addAttribute("pageTitle", "Overview");
        return "admin/home";
    }
}
