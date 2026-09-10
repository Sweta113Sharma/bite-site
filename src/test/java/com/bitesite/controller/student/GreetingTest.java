package com.bitesite.controller.student;

import com.bitesite.config.BusinessClock;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The greeting was reported saying "Good afternoon" at 18:19 in India, because the server
 * runs in UTC and the code read the JVM's zone.
 */
class GreetingTest {

    private String greetingAt(String utcInstant, String zone) {
        BusinessClock clock = new BusinessClock(
                Clock.fixed(Instant.parse(utcInstant), ZoneId.of(zone)));
        return MenuBrowseController.greetingFor(clock.time().getHour());
    }

    /** The exact moment this was reported. */
    @Test
    void saysGoodEveningAtQuarterPastSixInIndia() {
        assertThat(greetingAt("2026-09-10T12:49:00Z", "Asia/Kolkata")).isEqualTo("Good evening");
        // What production actually said, from the same instant, on the server's clock.
        assertThat(greetingAt("2026-09-10T12:49:00Z", "UTC")).isEqualTo("Good afternoon");
    }

    @Test
    void coversTheRestOfTheIndianDay() {
        assertThat(greetingAt("2026-09-10T01:00:00Z", "Asia/Kolkata")).isEqualTo("Good morning");   // 06:30
        assertThat(greetingAt("2026-09-10T06:00:00Z", "Asia/Kolkata")).isEqualTo("Good morning");   // 11:30
        assertThat(greetingAt("2026-09-10T07:00:00Z", "Asia/Kolkata")).isEqualTo("Good afternoon"); // 12:30
        assertThat(greetingAt("2026-09-10T11:00:00Z", "Asia/Kolkata")).isEqualTo("Good afternoon"); // 16:30
        assertThat(greetingAt("2026-09-10T11:31:00Z", "Asia/Kolkata")).isEqualTo("Good evening");   // 17:01
        assertThat(greetingAt("2026-09-10T18:00:00Z", "Asia/Kolkata")).isEqualTo("Good evening");   // 23:30
    }
}
