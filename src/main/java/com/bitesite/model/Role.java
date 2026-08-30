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
    SUPER_ADMIN,
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
        return this == SUPER_ADMIN || this == TECH_MANAGER;
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
