package com.bitesite.config;

import com.bitesite.model.Role;

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
            // Both outlet roles land on the queue. It is the shift-opening screen for an
            // operator, and the manager's own menu work is one nav click away — landing a
            // manager somewhere the operator never goes would make the two portals feel
            // like different products.
            case CANTEEN_MANAGER, CANTEEN_OPERATOR -> "/canteen/queue";
            case USER -> "/student/menu";
        };
    }

    // forAuthorities/forAuthority were removed here. They duplicated the mapping above by
    // matching on "ROLE_" strings behind a `default -> "/login"`, which meant a role added
    // or renamed anywhere else compiled cleanly and silently sent that role to the login
    // page. Nothing called them. The switch above is exhaustive over Role with no default,
    // so it fails the build instead — which is exactly how this change was found.
}
