package com.bitesite.model;

/**
 * The named grievance officer published on {@code /grievance-policy}.
 *
 * <p>{@link #isComplete()} is the whole point of this type. India's DPDP Act requires a
 * real person, a contact address and a response timeframe, and the policy page was
 * written to stay blank rather than show a placeholder, on the reasoning that a fake
 * officer is worse than a missing one. Rendering therefore asks this one question instead
 * of testing four fields at four call sites, where a later edit could quietly start
 * publishing a half-filled officer.
 *
 * <p>Designation is the one optional field: "Yash Gadia, grievance@bitesite.in, <address>,
 * 30 days" is a lawful published officer whether or not it also says "Founder".
 */
public record GrievanceOfficer(
        String name,
        String designation,
        String email,
        String address,
        String responseWindow) {

    public static final String KEY_NAME = "grievance.officer.name";
    public static final String KEY_DESIGNATION = "grievance.officer.designation";
    public static final String KEY_EMAIL = "grievance.officer.email";
    public static final String KEY_ADDRESS = "grievance.officer.address";
    public static final String KEY_RESPONSE_WINDOW = "grievance.officer.responseWindow";

    /** True only when every legally required field is filled. Drives publish-or-warn. */
    public boolean isComplete() {
        return filled(name) && filled(email) && filled(address) && filled(responseWindow);
    }

    private static boolean filled(String s) {
        return s != null && !s.isBlank();
    }
}
