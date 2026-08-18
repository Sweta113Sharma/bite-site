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

    // --- Outlet portal role ---
    CANTEEN_STAFF,

    // --- App portal role (was STUDENT, now USER for broader applicability) ---
    USER;

    /** True if this role belongs to the admin portal (admin.bitesite.in). */
    public boolean isAdminPortalRole() {
        return this == SUPER_ADMIN || this == TECH_MANAGER;
    }

    /** True if this role belongs to the outlet portal (outlet.bitesite.in). */
    public boolean isOutletPortalRole() {
        return this == CANTEEN_STAFF;
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
