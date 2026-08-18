package com.bitesite.model;

import java.util.Set;

/**
 * Scoped privilege tiers for the admin portal — the SpeedoExpress pattern, sized to
 * BiteSite's actual two admin-portal roles (SUPER_ADMIN, TECH_MANAGER). Pass the
 * relevant scope to {@code PortalGuard.requireScope()} per controller method.
 */
public final class StaffScope {

    private StaffScope() {}

    /** Operational scope: approvals, grievances. */
    public static final Set<Role> OPS_SCOPE = Set.of(Role.SUPER_ADMIN, Role.TECH_MANAGER);

    /** Technical scope: catalog, pricing, feature flags, system health. */
    public static final Set<Role> TECH_SCOPE = Set.of(Role.SUPER_ADMIN, Role.TECH_MANAGER);

    /** Full admin: user management, audit log, tenant CRUD, sales/onboarding pipeline —
     * SUPER_ADMIN only. */
    public static final Set<Role> FULL_ADMIN = Set.of(Role.SUPER_ADMIN);
}
