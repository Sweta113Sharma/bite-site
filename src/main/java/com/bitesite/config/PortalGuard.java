package com.bitesite.config;

import com.bitesite.model.Role;
import com.bitesite.model.User;
import org.springframework.security.access.AccessDeniedException;

import java.util.Set;

/**
 * Reusable guard methods for feature-gating within a portal — the SpeedoExpress
 * {@code requireStaff(req, scope)} pattern, adapted for server-side Spring MVC.
 *
 * <p>Usage in a controller method:
 * <pre>
 *   PortalGuard.requireScope(user, StaffScope.FULL_ADMIN);
 * </pre>
 *
 * <p>Throws {@link AccessDeniedException} (→ 403) if the user's active role is
 * not in the required scope.
 */
public final class PortalGuard {

    private PortalGuard() {}

    /**
     * Throws if the user's active role is not in the given scope.
     * This is the per-feature gate within the admin portal.
     */
    public static void requireScope(User user, Set<Role> scope) {
        if (user == null || user.getActiveRole() == null) {
            throw new AccessDeniedException("No active role");
        }
        if (!scope.contains(user.getActiveRole())) {
            throw new AccessDeniedException(
                    "Role " + user.getActiveRole() + " is not authorized for this operation");
        }
    }

    /**
     * Throws if the user is not a SUPER_ADMIN — by their durable grant
     * (user_roles), NOT by their transient active_role. This is the
     * SpeedoExpress principle: "authorize on the grant, not the view-mode."
     *
     * <p>A super-admin who is currently viewing as TECH_MANAGER is still
     * a super-admin and should pass this check.
     */
    public static void requireSuperAdmin(User user) {
        if (user == null || !user.hasRole(Role.SUPER_ADMIN)) {
            throw new AccessDeniedException("Super admin access required");
        }
    }

    /**
     * Throws if the user doesn't hold any staff-level role in their entitlements.
     */
    public static void requireStaff(User user) {
        if (user == null || user.getRoles() == null) {
            throw new AccessDeniedException("Staff access required");
        }
        boolean hasStaffRole = user.getRoles().stream().anyMatch(Role::isStaffRole);
        if (!hasStaffRole) {
            throw new AccessDeniedException("Staff access required");
        }
    }
}
