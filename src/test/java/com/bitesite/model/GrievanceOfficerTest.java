package com.bitesite.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the publish-or-warn rule on {@code /grievance-policy}.
 *
 * <p>The page names a real person as the legal contact for the whole platform, and the
 * failure that matters is publishing a half-filled one: an officer with a name but no way
 * to reach them, or a promised deadline with nobody attached. That is the placeholder
 * problem the page was written to avoid, so the check that prevents it is worth pinning
 * field by field rather than trusting one happy-path assertion.
 */
class GrievanceOfficerTest {

    private static GrievanceOfficer full() {
        return new GrievanceOfficer("Yash Gadia", "Founder", "grievance@bitesite.in",
                "12 Example Road, Greater Noida", "within 30 days");
    }

    @Test
    void completeWhenEveryRequiredFieldIsPresent() {
        assertThat(full().isComplete()).isTrue();
    }

    @Test
    void designationIsOptional() {
        assertThat(new GrievanceOfficer("Yash Gadia", null, "grievance@bitesite.in",
                "12 Example Road", "within 30 days").isComplete()).isTrue();
    }

    @Test
    void incompleteWhenAnyRequiredFieldIsMissing() {
        assertThat(new GrievanceOfficer(null, "Founder", "a@b.in", "addr", "30 days").isComplete())
                .as("no name").isFalse();
        assertThat(new GrievanceOfficer("Yash", "Founder", null, "addr", "30 days").isComplete())
                .as("no email").isFalse();
        assertThat(new GrievanceOfficer("Yash", "Founder", "a@b.in", null, "30 days").isComplete())
                .as("no address").isFalse();
        assertThat(new GrievanceOfficer("Yash", "Founder", "a@b.in", "addr", null).isComplete())
                .as("no response window").isFalse();
    }

    @Test
    void blankIsTreatedAsMissing() {
        // A cleared text input posts "" rather than null, so emptiness has to count as
        // absent or wiping a field would leave the page publishing the rest of the officer.
        assertThat(new GrievanceOfficer("   ", "Founder", "a@b.in", "addr", "30 days").isComplete())
                .as("whitespace name").isFalse();
        assertThat(new GrievanceOfficer("Yash", "Founder", "", "addr", "30 days").isComplete())
                .as("empty email").isFalse();
    }

    @Test
    void nothingSetAtAllIsIncomplete() {
        assertThat(new GrievanceOfficer(null, null, null, null, null).isComplete()).isFalse();
    }
}
