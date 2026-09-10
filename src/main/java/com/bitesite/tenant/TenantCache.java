package com.bitesite.tenant;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The logged-in user's college, without asking the database on every single request.
 *
 * <p>{@link com.bitesite.config.TenantResolutionInterceptor} needs the tenant for every
 * authenticated request, and was issuing {@code SELECT * FROM tenants WHERE id = ?} each
 * time. That is a primary-key hit on a table with a handful of rows, so it costs nothing
 * locally and is invisible in development. In production the database is in a different
 * region from the app, so it is a network round trip on every request, for data that
 * changes approximately never.
 *
 * <p>Correctness here is about the one case that matters: suspending a college has to take
 * effect straight away, not whenever a cache feels like expiring. So this is invalidated
 * explicitly on every write in {@link com.bitesite.service.TenantService}, and a suspended
 * tenant stops being usable on the very next request.
 *
 * <p>The TTL is a backstop, not the mechanism. It exists only for the day this runs on more
 * than one instance: a write on instance A cannot invalidate the map on instance B, so
 * without it a suspension could sit stale on another node indefinitely. One minute bounds
 * that. On the single instance this runs on today, explicit invalidation means the TTL never
 * decides anything.
 */
@Component
@RequiredArgsConstructor
public class TenantCache {

    /** Deliberately short: this is the multi-instance backstop, not the invalidation. */
    private static final Duration TTL = Duration.ofMinutes(1);

    private final TenantDao tenantDao;
    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();

    private record Entry(Tenant tenant, long expiresAtMillis) {
        boolean isFresh() {
            return System.currentTimeMillis() < expiresAtMillis;
        }
    }

    public Optional<Tenant> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        Entry cached = cache.get(id);
        if (cached != null && cached.isFresh()) {
            return Optional.ofNullable(cached.tenant());
        }
        // A miss stores the absence too. A request for a tenant that does not exist should
        // not re-ask the database on every subsequent request either.
        Tenant loaded = tenantDao.findById(id).orElse(null);
        cache.put(id, new Entry(loaded, System.currentTimeMillis() + TTL.toMillis()));
        return Optional.ofNullable(loaded);
    }

    /** Call after any write to a tenant. Cheap, and the thing that keeps suspension prompt. */
    public void invalidate(Long id) {
        if (id != null) {
            cache.remove(id);
        }
    }

    public void invalidateAll() {
        cache.clear();
    }
}
