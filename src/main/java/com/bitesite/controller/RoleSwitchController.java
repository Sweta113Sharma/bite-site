package com.bitesite.controller;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.RoleLandingPages;
import com.bitesite.dao.UserDao;
import com.bitesite.model.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The only way {@code active_role} changes. Validates that the requested role
 * is in the caller's entitlements (no self-elevation), persists it to the DB,
 * refreshes the Spring Security context, and redirects to the landing page
 * for the new role.
 *
 * <p>This is Layer 4 of the SpeedoExpress RBAC pattern — the controlled
 * switching mechanism.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class RoleSwitchController {

    private final UserDao userDao;
    private final SecurityContextRepository securityContextRepository;

    @PostMapping("/api/role/switch")
    public String switchRole(@RequestParam("role") String roleName,
                             @AuthenticationPrincipal AppUserPrincipal principal,
                             HttpServletRequest request, HttpServletResponse response) {
        Role requestedRole;
        try {
            requestedRole = Role.valueOf(roleName.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid role switch attempt: role={} user={}", roleName, principal.getUsername());
            return "redirect:/login?error";
        }

        var user = principal.getUser();

        // Entitlement check: can only switch into a role you already hold
        if (!user.hasRole(requestedRole)) {
            log.warn("Unauthorized role switch attempt: user={} requested={} entitled={}",
                    user.getEmail(), requestedRole, user.getRoles());
            return "redirect:/login?error";
        }

        // Persist the switch
        userDao.updateActiveRole(user.getId(), requestedRole);
        user.setActiveRole(requestedRole);

        // Refresh the Spring Security context — AppUserPrincipal#getAuthorities() derives
        // from user.getActiveRole(), already updated above, so this authority set already
        // reflects the new role. Explicitly saving to the repository (not just the
        // ThreadLocal SecurityContextHolder) is what makes it stick for the *next* request:
        // SecurityContextHolderFilter loads the context at the start of a request but
        // doesn't auto-persist further changes the way the older
        // SecurityContextPersistenceFilter did.
        Authentication newAuth = new UsernamePasswordAuthenticationToken(
                principal, principal.getPassword(), principal.getAuthorities());
        SecurityContext newContext = SecurityContextHolder.createEmptyContext();
        newContext.setAuthentication(newAuth);
        SecurityContextHolder.setContext(newContext);
        securityContextRepository.saveContext(newContext, request, response);

        log.info("Role switched: user={} newActiveRole={}", user.getEmail(), requestedRole);

        // Redirect to the landing page for the new role
        return "redirect:" + RoleLandingPages.forActiveRole(requestedRole);
    }
}
