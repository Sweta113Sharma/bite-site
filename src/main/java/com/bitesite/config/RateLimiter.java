package com.bitesite.config;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple in-memory fixed-window rate limiter — deliberately not Redis-backed. Fine for a
 * single-instance deployment; horizontally scaling the app would need a shared store
 * instead (noted in the README as a scaling follow-up, not silently ignored).
 */
@Component
public class RateLimiter {

    private record Window(long windowStartMillis, AtomicInteger count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** Returns true if this call is within the allowed rate, false if the caller should be
     * rejected. */
    public boolean tryConsume(String key, int maxAttempts, Duration window) {
        long now = System.currentTimeMillis();
        long windowMillis = window.toMillis();
        Window slot = windows.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStartMillis() > windowMillis) {
                return new Window(now, new AtomicInteger(1));
            }
            existing.count().incrementAndGet();
            return existing;
        });
        return slot.count().get() <= maxAttempts;
    }

    /** Prevents unbounded growth from one-off keys (e.g. an IP that never comes back). */
    @Scheduled(fixedDelay = 15 * 60_000)
    public void evictStale() {
        long cutoff = System.currentTimeMillis() - Duration.ofHours(1).toMillis();
        windows.entrySet().removeIf(e -> e.getValue().windowStartMillis() < cutoff);
    }
}
