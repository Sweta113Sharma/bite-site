package com.bitesite.config;

import com.bitesite.model.Role;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/** Single source of truth for "where does this role land after login" — used by the login
 * success handler and by the "/" redirect, so the two never drift apart. */
public final class RoleLandingPages {

    private RoleLandingPages() {
    }

    /**
     * Determines the landing page based on the user's active role.
     * For multi-role users, this uses activeRole (the "view-mode"), not the full set.
     */
    public static String forActiveRole(Role activeRole) {
        if (activeRole == null) return "/login";
        return switch (activeRole) {
            case SUPER_ADMIN -> "/admin";
            case TECH_MANAGER -> "/techmgr";
            case CANTEEN_STAFF -> "/canteen/queue";
            case USER -> "/student/menu";
        };
    }

    /**
     * Legacy method — resolves from Spring Security authorities.
     * Used as fallback when activeRole is not available.
     */
    public static String forAuthorities(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .map(RoleLandingPages::forAuthority)
                .orElse("/login");
    }

    private static String forAuthority(String authority) {
        return switch (authority) {
            case "ROLE_SUPER_ADMIN" -> "/admin";
            case "ROLE_TECH_MANAGER" -> "/techmgr";
            case "ROLE_CANTEEN_STAFF" -> "/canteen/queue";
            case "ROLE_USER" -> "/student/menu";
            default -> "/login";
        };
    }
}
