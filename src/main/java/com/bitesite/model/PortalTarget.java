package com.bitesite.model;

import java.util.Set;

/**
 * Which deployment/subdomain a request is targeting. In local dev this is
 * resolved from the {@code Host} header; in production it can be pinned
 * via the {@code APP_TARGET} environment variable so each deploy only
 * serves routes for one portal.
 */
public enum PortalTarget {
    /** app.bitesite.in — the customer ordering portal. */
    APP,

    /** outlet.bitesite.in — canteen staff operations. */
    OUTLET,

    /** admin.bitesite.in — back-office: super admin, tech, sales, support. */
    ADMIN;

    private static final Set<Role> APP_ROLES    = Set.of(Role.USER);
    private static final Set<Role> OUTLET_ROLES = Set.of(Role.CANTEEN_STAFF);
    private static final Set<Role> ADMIN_ROLES  = Set.of(Role.SUPER_ADMIN, Role.TECH_MANAGER);

    /** Whether the given role is allowed to operate on this portal. */
    public boolean allowsRole(Role role) {
        return rolesForPortal().contains(role);
    }

    /** The set of roles that this portal admits. */
    public Set<Role> rolesForPortal() {
        return switch (this) {
            case APP    -> APP_ROLES;
            case OUTLET -> OUTLET_ROLES;
            case ADMIN  -> ADMIN_ROLES;
        };
    }
}
