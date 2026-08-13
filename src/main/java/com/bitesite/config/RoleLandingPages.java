package com.bitesite.config;

import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/** Single source of truth for "where does this role land after login" — used by the login
 * success handler and by the "/" redirect, so the two never drift apart. */
public final class RoleLandingPages {

    private RoleLandingPages() {
    }

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
            case "ROLE_STUDENT" -> "/student/menu";
            default -> "/login";
        };
    }
}
