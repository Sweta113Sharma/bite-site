package com.bitesite.model;

import java.util.Set;

/**
 * Scoped privilege tiers within a portal. Pass the relevant scope to
 * {@code PortalGuard.requireScope()} as the first statement of a controller method.
 *
 * <p>Two layers, deliberately: the URL rules in {@code SecurityConfig} decide which
 * portal a role may enter at all, and these scopes decide what it may do once inside.
 * The admin portal has worked this way from the start; the outlet portal joined it when
 * CANTEEN_STAFF was split, having previously had no per-method gating whatsoever.
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

    // ---- Outlet portal ----
    //
    // The dividing line: an operator changes what is true today, a manager changes what
    // the outlet *is*. Everything below falls out of that one sentence.

    /** Day-to-day operations: the live queue, and anything that only describes today.
     * Both outlet roles — a manager working a shift alone still has to run the counter,
     * and locking them out would strand an outlet whose operator is off. */
    public static final Set<Role> OUTLET_OPS = Set.of(Role.CANTEEN_MANAGER, Role.CANTEEN_OPERATOR);

    /** Configuration: what the menu is — items, prices, discounts, daily limits — plus
     * outlet settings and staff. Manager only. */
    public static final Set<Role> OUTLET_MANAGE = Set.of(Role.CANTEEN_MANAGER);
}
