package com.bitesite.web;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.PortalResolver;
import com.bitesite.config.TenantContext;
import com.bitesite.model.PortalTarget;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import com.bitesite.service.Cart;
import com.bitesite.tenant.Tenant;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

/** Makes request-wide context available to every Thymeleaf view without each controller
 * having to add it explicitly. */
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalModelAttributes {

    private final PortalResolver portalResolver;
    private final Cart cart;

    /** Total item count in the student's session cart, for the bottom-nav badge and
     * sticky cart bar — rendered on every page so it's already correct on first paint,
     * with no post-load flash while JS catches up. */
    @ModelAttribute("cartItemCount")
    public int cartItemCount() {
        return cart.getQuantities().values().stream().mapToInt(Integer::intValue).sum();
    }

    @ModelAttribute("currentTenant")
    public Tenant currentTenant() {
        return TenantContext.get();
    }

    /** Other roles this user holds that are also valid on the portal they're currently on
     * — i.e. what the navbar's role-switcher should offer. Empty for the common case of a
     * user with exactly one role per portal, so the switcher simply doesn't render. */
    @ModelAttribute("switchableRoles")
    public List<Role> switchableRoles(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserPrincipal principal)) {
            return List.of();
        }
        User user = principal.getUser();
        if (user.getRoles() == null || user.getRoles().size() < 2) {
            return List.of();
        }
        PortalTarget portal = portalResolver.resolve(request);
        return user.getRoles().stream()
                .filter(r -> r != user.getActiveRole())
                .filter(portal.rolesForPortal()::contains)
                .toList();
    }
}
