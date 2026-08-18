package com.bitesite.config;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Fixed-window rate limiter backed by MySQL rather than process memory, so login/checkout
 * throttling stays correct across app restarts and works properly if the app is ever
 * scaled to more than one instance — every instance shares the same table instead of
 * keeping its own independent count.
 *
 * <p>The upsert uses MySQL's {@code LAST_INSERT_ID(expr)} idiom to atomically compute and
 * return the post-increment count in a single round trip, rather than an UPSERT followed
 * by a separate SELECT that could race under concurrent requests for the same key.
 */
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private final JdbcTemplate jdbcTemplate;

    /** Returns true if this call is within the allowed rate, false if the caller should be
     * rejected. */
    public boolean tryConsume(String key, int maxAttempts, Duration window) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(window);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO rate_limit_window (rate_key, window_start, attempt_count) "
                            + "VALUES (?, ?, LAST_INSERT_ID(1)) "
                            + "ON DUPLICATE KEY UPDATE "
                            + "  attempt_count = LAST_INSERT_ID(IF(window_start > ?, attempt_count + 1, 1)), "
                            + "  window_start = IF(window_start > ?, window_start, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, key);
            ps.setTimestamp(2, Timestamp.from(now));
            ps.setTimestamp(3, Timestamp.from(cutoff));
            ps.setTimestamp(4, Timestamp.from(cutoff));
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keyHolder);

        // MySQL Connector/J duplicates the generated-key row for INSERT ... ON DUPLICATE
        // KEY UPDATE (it mirrors the server's "2 rows affected" convention for an update
        // via upsert), so keyHolder.getKey() throws on "multiple keys" here even though
        // every duplicate reports the same LAST_INSERT_ID() value. Read the first one.
        long attemptCount = ((Number) keyHolder.getKeyList().get(0).get("GENERATED_KEY")).longValue();
        return attemptCount <= maxAttempts;
    }

    /** Prevents unbounded table growth from one-off keys (e.g. an IP that never comes back). */
    @Scheduled(fixedDelay = 15 * 60_000)
    public void evictStale() {
        jdbcTemplate.update(
                "DELETE FROM rate_limit_window WHERE window_start < ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(1))));
    }
}
