package com.bitesite.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sweeper and the windows have to agree.
 *
 * <p>{@code evictStale} deletes rows by age. Delete one whose window is still open and the
 * count goes with it, so the limit silently resets and enforces nothing — which is exactly
 * what happened the moment a daily budget was added under an hourly sweep. This pins the
 * relationship so the next long window does not quietly reintroduce it.
 */
class RateLimiterWindowTest {

    /** The longest window any caller actually passes to tryConsume — the order-email
     * budget in OrderNotifier. */
    private static final Duration LONGEST_CALLER_WINDOW = Duration.ofDays(1);

    @Test
    void theSweeperNeverDeletesAWindowThatCouldStillBeOpen() throws Exception {
        Field field = RateLimiter.class.getDeclaredField("LONGEST_WINDOW");
        field.setAccessible(true);
        Duration sweepAfter = (Duration) field.get(null);

        assertThat(sweepAfter)
                .as("eviction must lag the longest window in use, or it resets counts early")
                .isGreaterThan(LONGEST_CALLER_WINDOW);
    }
}
