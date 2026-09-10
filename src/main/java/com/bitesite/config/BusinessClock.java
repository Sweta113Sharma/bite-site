package com.bitesite.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * The clock the business runs on, as opposed to the one the server happens to be set to.
 *
 * <p>{@code LocalTime.now()} reads the JVM's default zone. In production that is UTC,
 * because Azure runs its containers in UTC, so anything asking "what time is it" got an
 * answer five and a half hours behind every canteen using the product. It said "Good
 * afternoon" at 18:19 in India.
 *
 * <p>{@link com.bitesite.dao.OrderDao} already documented this gap from the other side:
 * date boundaries were pushed down into SQL precisely because there was "no configured
 * business timezone to check it against". This is that timezone. Anything deciding what
 * day or hour it is for a human should read it here rather than calling {@code now()}
 * directly.
 *
 * <p>Defaults to Asia/Kolkata, and settable with {@code APP_TIMEZONE} for the day the
 * platform serves a campus that is not in India. An unparseable value fails at startup,
 * which is the right moment to find out.
 */
@Component
public class BusinessClock {

    private final Clock clock;

    @Autowired
    public BusinessClock(@Value("${app.timezone:Asia/Kolkata}") String timezone) {
        this(Clock.system(ZoneId.of(timezone)));
    }

    /** Lets a caller pin the instant and the zone; used by tests to remove wall-clock flake. */
    public BusinessClock(Clock clock) {
        this.clock = clock;
    }

    public ZoneId zone() {
        return clock.getZone();
    }

    public LocalTime time() {
        return LocalTime.now(clock);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
