package com.bitesite.config;

import com.bitesite.tenant.Tenant;
import com.bitesite.tenant.TenantDao;
import com.bitesite.tenant.TenantStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Sets {@link TenantContext} from the logged-in user's own account — never from a URL,
 * header, or client-supplied value. Which college's menu/orders/etc. someone sees is
 * decided entirely by which account they authenticated as. Runs as an MVC interceptor
 * (not a servlet filter) specifically so it executes after Spring Security has already
 * established the {@link Authentication} for the request.
 */
@Component
@RequiredArgsConstructor
public class TenantResolutionInterceptor implements HandlerInterceptor {

    private final TenantDao tenantDao;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AppUserPrincipal principal)) {
            return true;
        }

        Long tenantId = principal.getUser().getTenantId();
        if (tenantId == null) {
            return true;
        }

        Tenant tenant = tenantDao.findById(tenantId).orElse(null);
        TenantContext.set(tenant);

        boolean tenantUsable = tenant != null && tenant.getStatus() == TenantStatus.ACTIVE;
        String uri = request.getRequestURI();
        boolean isEscapeRoute = uri.equals("/tenant-unavailable") || uri.equals("/logout");
        if (!tenantUsable && !isEscapeRoute) {
            response.sendRedirect(request.getContextPath() + "/tenant-unavailable");
            return false;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        TenantContext.clear();
    }
}
