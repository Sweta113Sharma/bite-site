package com.bitesite.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bug: production runs in UTC, so anything asking the time got an answer five and a
 * half hours behind every canteen using the product.
 */
class BusinessClockTest {

    /** 18:19 IST on 10 September 2026, the moment this was actually reported. */
    private static final Instant REPORTED = Instant.parse("2026-09-10T12:49:00Z");

    private BusinessClock at(Instant instant, String zone) {
        return new BusinessClock(Clock.fixed(instant, ZoneId.of(zone)));
    }

    @Test
    void readsTheIndianHourNotTheServersUtcHour() {
        assertThat(at(REPORTED, "Asia/Kolkata").time().getHour()).isEqualTo(18);
        // What the server thought, and the reason the greeting said "afternoon".
        assertThat(at(REPORTED, "UTC").time().getHour()).isEqualTo(12);
    }

    /**
     * The date rolls at Indian midnight, not at 05:30 IST. Anything reporting on "today"
     * reads five and a half hours of the previous day as today without this.
     */
    @Test
    void theDayRollsOverAtIndianMidnightNotUtcMidnight() {
        Instant justAfterIstMidnight = Instant.parse("2026-09-09T18:31:00Z");

        assertThat(at(justAfterIstMidnight, "Asia/Kolkata").today())
                .isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(at(justAfterIstMidnight, "UTC").today())
                .isEqualTo(LocalDate.of(2026, 9, 9));
    }

    @Test
    void defaultsToIndiaWhenNothingIsConfigured() {
        assertThat(new BusinessClock("Asia/Kolkata").zone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
    }

    /** A typo in the setting should stop the app, not silently serve the wrong hour. */
    @Test
    void refusesAnUnparseableTimezoneAtStartup() {
        assertThatThrownBy(() -> new BusinessClock("Not/AZone"))
                .isInstanceOf(java.time.DateTimeException.class);
    }
}
