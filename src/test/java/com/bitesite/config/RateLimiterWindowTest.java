package com.bitesite.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sweeper and the windows have to agree.
 *
 * <p>{@code evictStale} deletes rows by age. Delete one whose window is still open and the
 * count goes with it, so the limit silently resets and enforces nothing. This pins the
 * sweeper above the longest window the limiter claims to support, so adding one is safe.
 */
class RateLimiterWindowTest {

    /** The longest window the limiter claims to support. Callers currently pass minutes;
     * this is the ceiling eviction has to respect so that adding a longer one later is
     * safe rather than silently broken. */
    private static final Duration LONGEST_SUPPORTED_WINDOW = Duration.ofDays(1);

    @Test
    void theSweeperNeverDeletesAWindowThatCouldStillBeOpen() throws Exception {
        Field field = RateLimiter.class.getDeclaredField("LONGEST_WINDOW");
        field.setAccessible(true);
        Duration sweepAfter = (Duration) field.get(null);

        assertThat(sweepAfter)
                .as("eviction must lag the longest window in use, or it resets counts early")
                .isGreaterThan(LONGEST_SUPPORTED_WINDOW);
    }
}
