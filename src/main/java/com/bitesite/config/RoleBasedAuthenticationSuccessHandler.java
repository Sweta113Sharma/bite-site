package com.bitesite.config;

import com.bitesite.dao.UserDao;
import com.bitesite.model.PortalTarget;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * Reconciles {@code active_role} against the portal being logged into before deciding
 * where to send the user. {@code active_role} is whatever was last persisted — possibly
 * from a session on a *different* portal — so it can't be trusted blindly at login time;
 * using it as-is would either land a multi-role user on a page their current role can't
 * reach on this subdomain, or (worse) silently let a role through that has nothing to do
 * with the portal they're actually signing into.
 *
 * <p>Resolution: if the persisted active_role already fits this portal, keep it (least
 * surprising for a returning user). Otherwise, pick a role from the user's entitlements
 * that does fit — preferring SUPER_ADMIN on the admin portal — and persist that as the
 * new active_role. If they hold no role valid for this portal at all, the login is
 * rejected outright with a clear reason, rather than succeeding into a session that's
 * just going to get bounced by {@link PortalGateFilter} on the very next request.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoleBasedAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final PortalResolver portalResolver;
    private final UserDao userDao;
    private final SecurityContextRepository securityContextRepository;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        if (!(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            getRedirectStrategy().sendRedirect(request, response, "/login");
            return;
        }

        User user = principal.getUser();
        PortalTarget portal = portalResolver.resolve(request);
        Set<Role> eligible = portal.rolesForPortal();

        Role resolvedActiveRole = user.getActiveRole();
        if (resolvedActiveRole == null || !eligible.contains(resolvedActiveRole)) {
            boolean superAdminEligible = eligible.contains(Role.SUPER_ADMIN) && user.hasRole(Role.SUPER_ADMIN);
            resolvedActiveRole = superAdminEligible ? Role.SUPER_ADMIN
                    : user.getRoles().stream().filter(eligible::contains).findFirst().orElse(null);
        }

        if (resolvedActiveRole == null) {
            log.info("Login rejected: user={} holds no role valid for portal={} (roles={})",
                    user.getEmail(), portal, user.getRoles());
            request.getSession().invalidate();
            SecurityContextHolder.clearContext();
            getRedirectStrategy().sendRedirect(request, response, "/login?error=noaccess");
            return;
        }

        if (resolvedActiveRole != user.getActiveRole()) {
            userDao.updateActiveRole(user.getId(), resolvedActiveRole);
            user.setActiveRole(resolvedActiveRole);
            // Authorities are derived from activeRole (see AppUserPrincipal) — refresh the
            // context so this request's redirect target and the resulting session agree.
            // By the time this handler runs, the *original* (un-reconciled) authentication
            // has already been saved to the session by the framework's own login-success
            // flow — so the update has to be saved again explicitly here, or it never makes
            // it past this one request/response cycle (see SecurityContextRepository bean
            // javadoc in SecurityConfig for why).
            Authentication refreshed = new UsernamePasswordAuthenticationToken(
                    principal, authentication.getCredentials(), principal.getAuthorities());
            SecurityContext newContext = SecurityContextHolder.createEmptyContext();
            newContext.setAuthentication(refreshed);
            SecurityContextHolder.setContext(newContext);
            securityContextRepository.saveContext(newContext, request, response);
        }

        getRedirectStrategy().sendRedirect(request, response, RoleLandingPages.forActiveRole(resolvedActiveRole));
    }
}
