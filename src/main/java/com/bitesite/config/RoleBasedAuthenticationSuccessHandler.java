package com.bitesite.config;

import com.bitesite.dao.UserDao;
import com.bitesite.service.EmailService;
import com.bitesite.service.OtpService;
import com.bitesite.model.PortalTarget;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    /** Where the half-finished sign-in is remembered between the password and the code. */
    public static final String PENDING_2FA_USER_ID = "pending2faUserId";

    private final PortalResolver portalResolver;
    private final UserDao userDao;
    private final SecurityContextRepository securityContextRepository;
    private final OtpService otpService;
    private final EmailService emailService;

    /**
     * Escape hatch. A second factor delivered by email means a broken relay locks every
     * platform account out of the console — including the account that would go and fix
     * the relay. Set {@code ADMIN_2FA_ENABLED=false} and restart to get back in.
     */
    @Value("${app.security.admin-2fa:true}")
    private boolean adminTwoFactorEnabled;

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

        if (requiresSecondFactor(user)) {
            startSecondFactor(request, response, user, resolvedActiveRole);
            return;
        }

        getRedirectStrategy().sendRedirect(request, response, RoleLandingPages.forActiveRole(resolvedActiveRole));
    }

    /**
     * Platform accounts only. A super admin reaches every college's orders, payments and
     * audit trail, and a password was the whole of the front door.
     *
     * <p>Gated on email actually being configured, matching how every other optional
     * integration in this app behaves: with no way to deliver a code, demanding one would
     * lock the console rather than protect it. That does mean the factor is only as present
     * as the mail relay, which is why the flag above exists.
     */
    private boolean requiresSecondFactor(User user) {
        if (!adminTwoFactorEnabled || !emailService.isConfigured()) {
            return false;
        }
        return user.hasRole(Role.SUPER_ADMIN) || user.hasRole(Role.TECH_MANAGER);
    }

    /**
     * Drops the authenticated session and replaces it with a note of who is halfway in.
     *
     * <p>The framework has already saved an authenticated context by the time this handler
     * runs, so clearing the holder is not enough — the session itself has to go, or the
     * password alone would have produced a usable session and the code would be decoration.
     */
    private void startSecondFactor(HttpServletRequest request, HttpServletResponse response,
            User user, Role resolvedActiveRole) throws IOException {
        // Issue first. If no code could be sent there is nothing to challenge against, and
        // throwing the session away would lock the account out with no way back in short of
        // an environment variable and a restart.
        if (!otpService.issueLoginOtp(user)) {
            log.warn("Second factor skipped for {}: no code could be issued", user.getEmail());
            getRedirectStrategy().sendRedirect(request, response,
                    RoleLandingPages.forActiveRole(resolvedActiveRole));
            return;
        }

        request.getSession().invalidate();
        SecurityContextHolder.clearContext();
        securityContextRepository.saveContext(SecurityContextHolder.createEmptyContext(), request, response);

        HttpSession pending = request.getSession(true);
        pending.setAttribute(PENDING_2FA_USER_ID, user.getId());

        log.info("Second factor required for platform account {}", user.getEmail());
        getRedirectStrategy().sendRedirect(request, response, "/login/verify");
    }
}
