package com.bitesite.config;

import com.bitesite.model.PortalTarget;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Determines which portal (APP / OUTLET / ADMIN) the current request targets.
 *
 * <p>Two modes:
 * <ol>
 *   <li><b>Fixed target</b> (production split-deploy): set {@code APP_TARGET=APP|OUTLET|ADMIN}
 *       as an environment variable. Every request on that process is pinned to that portal.</li>
 *   <li><b>Host-header routing</b> (local dev): inspects the {@code Host} header.
 *       {@code admin.localhost} → ADMIN, {@code outlet.localhost} → OUTLET,
 *       everything else (including plain {@code localhost}) → APP.</li>
 * </ol>
 */
@Component
public class PortalResolver {

    @Value("${app.portal.target:#{null}}")
    private String fixedTarget;

    /**
     * Resolve the portal target for the current request.
     */
    public PortalTarget resolve(HttpServletRequest request) {
        // Mode 1: fixed target via env var
        if (fixedTarget != null && !fixedTarget.isBlank()) {
            return PortalTarget.valueOf(fixedTarget.trim().toUpperCase());
        }

        // Mode 2: host-header routing
        String host = request.getServerName().toLowerCase();
        if (host.startsWith("admin")) {
            return PortalTarget.ADMIN;
        }
        if (host.startsWith("outlet")) {
            return PortalTarget.OUTLET;
        }
        // Default: app portal (students/users)
        return PortalTarget.APP;
    }
}
