package com.bitesite.config;

import com.bitesite.model.PortalTarget;
import com.bitesite.model.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Enforces portal ↔ role gating: after Spring Security authenticates the request,
 * this filter checks whether the user's {@code activeRole} is allowed on the portal
 * determined by the {@link PortalResolver}.
 *
 * <p>If the user is authenticated but their active role doesn't belong to this portal,
 * they get a 403 — not a redirect, because they genuinely shouldn't be here. The client
 * should tell them "you're on the wrong portal" and offer a link to switch.
 *
 * <p>Unauthenticated requests, static resources, and public paths are passed through
 * untouched — Spring Security handles those.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalGateFilter extends OncePerRequestFilter {

    private final PortalResolver portalResolver;

    /** Paths that should never be gated — login, logout, static assets, public pages. */
    private static final Set<String> BYPASS_PREFIXES = Set.of(
            "/login", "/logout", "/register", "/css/", "/js/", "/img/", "/uploads/",
            "/error", "/actuator", "/api/payments/webhook",
            "/privacy-policy", "/terms", "/refund-policy", "/tenant-unavailable",
            "/verify", "/resend-verification",
            "/manifest.webmanifest", "/sw.js", "/offline.html",
            "/api/role/switch"  // role-switching must be accessible from any portal
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Let public paths through
        String uri = request.getRequestURI();
        if (isBypassed(uri)) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AppUserPrincipal principal)) {
            // Not yet authenticated — let Spring Security's login flow handle it
            filterChain.doFilter(request, response);
            return;
        }

        PortalTarget portal = portalResolver.resolve(request);
        Role activeRole = principal.getUser().getActiveRole();

        if (activeRole == null) {
            // Shouldn't happen with proper data, but fail closed
            log.warn("User {} has no active_role — denying access", principal.getUsername());
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Your account has no active role. Please contact an administrator.");
            return;
        }

        if (!portal.allowsRole(activeRole)) {
            log.info("Portal gate denied: user={} activeRole={} portal={}",
                    principal.getUsername(), activeRole, portal);
            // Send to a "wrong portal" error page instead of a raw 403
            request.setAttribute("portalTarget", portal);
            request.setAttribute("activeRole", activeRole);
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Your current role (" + activeRole + ") does not have access to this portal.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isBypassed(String uri) {
        if (uri.equals("/")) return true;
        for (String prefix : BYPASS_PREFIXES) {
            if (uri.startsWith(prefix)) return true;
        }
        return false;
    }
}
