package com.bitesite.config;

import com.bitesite.model.PortalTarget;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Portal resolution is what lets a single deployment serve every portal.
 *
 * <p>The Android apps load one host ({@code bitesite-app.azurewebsites.net}) and rely on
 * the path to pick the portal, so the path rules have to win over the host-header
 * fallback. These tests pin the ordering: {@code /canteen/**} → OUTLET, {@code /admin/**}
 * → ADMIN, and everything else falls back to the host rules (and finally APP).
 */
class PortalResolverTest {

    private PortalResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PortalResolver();
    }

    private HttpServletRequest request(String path, String host) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(path);
        when(req.getServerName()).thenReturn(host);
        return req;
    }

    @Test
    void canteenPathResolvesToOutletEvenOnTheAppHost() {
        // The exact case the outlet Android app hits: one host, a /canteen path.
        assertThat(resolver.resolve(request("/canteen/orders", "bitesite-app.azurewebsites.net")))
                .isEqualTo(PortalTarget.OUTLET);
    }

    @Test
    void canteenRootResolvesToOutlet() {
        assertThat(resolver.resolve(request("/canteen", "bitesite-app.azurewebsites.net")))
                .isEqualTo(PortalTarget.OUTLET);
    }

    @Test
    void adminPathResolvesToAdmin() {
        assertThat(resolver.resolve(request("/admin/outlets", "bitesite-app.azurewebsites.net")))
                .isEqualTo(PortalTarget.ADMIN);
    }

    @Test
    void appPathsStayOnTheAppPortal() {
        assertThat(resolver.resolve(request("/student/menu", "bitesite-app.azurewebsites.net")))
                .isEqualTo(PortalTarget.APP);
    }

    @Test
    void hostHeaderRoutingStillWorksForLocalDev() {
        assertThat(resolver.resolve(request("/student/menu", "outlet.localhost")))
                .isEqualTo(PortalTarget.OUTLET);
        assertThat(resolver.resolve(request("/student/menu", "admin.localhost")))
                .isEqualTo(PortalTarget.ADMIN);
    }

    @Test
    void pathRoutingWinsOverHostHeader() {
        // A /canteen path on an admin-looking host must still be OUTLET, not ADMIN.
        assertThat(resolver.resolve(request("/canteen", "admin.localhost")))
                .isEqualTo(PortalTarget.OUTLET);
    }
}
