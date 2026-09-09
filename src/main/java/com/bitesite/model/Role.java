package com.bitesite.model;

/**
 * Every role in the system. A user can hold multiple roles simultaneously
 * (stored in {@code user_roles}); the one they're currently operating as
 * is {@code active_role}.
 *
 * <p>Portal affinity: each role belongs to exactly one portal
 * (app / outlet / admin). The helpers below let security filters and
 * the role-switcher decide whether a user is allowed on a given portal.
 */
public enum Role {
    // --- Admin portal roles ---
    //
    // Declaration order is load-bearing here too. RoleBasedAuthenticationSuccessHandler
    // prefers SUPER_ADMIN explicitly and otherwise takes findFirst() over an EnumSet,
    // which iterates in declaration order — so ADMIN sits above TECH_MANAGER and someone
    // holding both signs into the admin portal as the more privileged of the two.
    SUPER_ADMIN,

    // Full run of the admin console, with one thing held back: an ADMIN cannot create,
    // grant or revoke an elevated role, and cannot touch an account that holds one. That
    // is what separates them from a SUPER_ADMIN, and the rule lives in RoleAssignment.
    ADMIN,

    TECH_MANAGER,

    // --- Outlet portal roles ---
    //
    // Declaration order is load-bearing. User.roles is an EnumSet, so it iterates in
    // declaration order, and RoleBasedAuthenticationSuccessHandler takes findFirst() over
    // the roles eligible for the portal being logged into. For someone holding both,
    // whichever is declared first is what they log in as. Manager leads deliberately —
    // the same "more privileged wins on ambiguity" rule the admin portal applies
    // explicitly for SUPER_ADMIN. Pinned by a test, because nothing else would catch a
    // reorder.
    CANTEEN_MANAGER,
    CANTEEN_OPERATOR,

    // --- App portal role (was STUDENT, now USER for broader applicability) ---
    USER;

    /** True if this role belongs to the admin portal (admin.bitesite.in). */
    public boolean isAdminPortalRole() {
        return this == SUPER_ADMIN || this == ADMIN || this == TECH_MANAGER;
    }

    /**
     * Roles that confer administrative power over other people's access.
     *
     * <p>Only a SUPER_ADMIN may grant or revoke one of these, and only a SUPER_ADMIN may
     * act on an account that holds one. Without the second half an ADMIN could not make
     * themselves a super admin, but could strip one — which is the same lockout by a
     * longer route.
     */
    public boolean isElevated() {
        return this == SUPER_ADMIN || this == ADMIN;
    }

    /** True if this role belongs to the outlet portal (outlet.bitesite.in). */
    public boolean isOutletPortalRole() {
        return this == CANTEEN_MANAGER || this == CANTEEN_OPERATOR;
    }

    /** True if this role belongs to the app portal (app.bitesite.in). */
    public boolean isAppPortalRole() {
        return this == USER;
    }

    /** True if this is a staff-level role (any role on the admin portal). */
    public boolean isStaffRole() {
        return isAdminPortalRole();
    }
}
