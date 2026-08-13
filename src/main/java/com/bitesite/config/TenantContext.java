package com.bitesite.config;

import com.bitesite.tenant.Tenant;

/**
 * The tenant resolved from the current request's subdomain, valid only for the lifetime of
 * that request/thread. Set once by {@link TenantResolutionFilter} before Spring Security's
 * chain runs, and always cleared in a {@code finally} so a pooled Tomcat thread never leaks
 * one request's tenant into the next. {@code null} means the platform (non-tenant) area —
 * e.g. the reserved {@code admin} subdomain.
 */
public final class TenantContext {

    private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Tenant tenant) {
        CURRENT.set(tenant);
    }

    public static Tenant get() {
        return CURRENT.get();
    }

    public static Long tenantId() {
        Tenant tenant = CURRENT.get();
        return tenant == null ? null : tenant.getId();
    }

    public static boolean isPlatformScope() {
        return CURRENT.get() == null;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
