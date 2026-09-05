package com.bitesite.config;

import com.bitesite.model.PortalTarget;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Determines which portal (APP / OUTLET / ADMIN) the current request targets.
 *
 * <p>Three modes:
 * <ol>
 *   <li><b>Fixed target</b> (production split-deploy): set {@code APP_TARGET=APP|OUTLET|ADMIN}
 *       as an environment variable. Every request on that process is pinned to that portal.</li>
 *   <li><b>Path-prefix routing</b>: {@code /canteen/**} → OUTLET, {@code /admin/**} → ADMIN.
 *       This is what makes a single deployment serve every portal, which is how the
 *       Android outlet app reaches {@code /canteen} — a WebView on
 *       {@code bitesite-app.azurewebsites.net} sends one host for every path, so
 *       host-header routing can never admit a canteen manager there.</li>
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

        // Mode 2: path-prefix routing. This comes before the host-header fallback so a
        // single deployment (one host, e.g. bitesite-app.azurewebsites.net) can serve
        // every portal — the Android apps load that one host and rely on the path to
        // pick the portal.
        String path = request.getRequestURI();
        if (path.startsWith("/canteen")) {
            return PortalTarget.OUTLET;
        }
        if (path.startsWith("/admin")) {
            return PortalTarget.ADMIN;
        }

        // Mode 3: host-header routing
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
