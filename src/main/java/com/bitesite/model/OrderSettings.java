package com.bitesite.model;

import java.util.Map;

/**
 * Platform-wide settings that govern how orders behave, as opposed to
 * {@link BillingSettings}' commercial terms.
 *
 * <p>A typed view over the same key/value table, for the same reason: a mistyped key fails
 * in one place rather than silently reading as "off" somewhere deep in an order path.
 *
 * @param selfCancelWindowSeconds how long a student may undo their own order after paying,
 *                                and equally how long the kitchen is kept from seeing it
 */
public record OrderSettings(int selfCancelWindowSeconds) {

    public static final String SELF_CANCEL_WINDOW = "orders.self_cancel_window_seconds";

    /**
     * What the window was for the whole time it was compiled in. Kept as the fallback so
     * that an unset row, a blank one, or a corrupted one all behave exactly as the
     * platform did before this became configurable, rather than defaulting to "off".
     */
    public static final int DEFAULT_SELF_CANCEL_WINDOW_SECONDS = 20;

    /**
     * The window is also the time the kitchen is blind to a paid order, so it cannot be set
     * to anything. Five minutes is generous headroom over the twenty seconds this shipped
     * with while keeping the blind spot bounded: past that, a student is waiting on food
     * that nobody has been told to cook.
     */
    public static final int MAX_SELF_CANCEL_WINDOW_SECONDS = 300;

    /** Reads the stored map, falling back to the compiled-in default for anything absent. */
    public static OrderSettings from(Map<String, String> s) {
        return new OrderSettings(window(s.get(SELF_CANCEL_WINDOW)));
    }

    /** False when the window is zero: no cancel button, and the kitchen sees orders at once. */
    public boolean selfCancelEnabled() {
        return selfCancelWindowSeconds > 0;
    }

    /** Holds any requested window inside the permitted range. Zero means the feature is off. */
    public static int clampWindow(int seconds) {
        return Math.max(0, Math.min(MAX_SELF_CANCEL_WINDOW_SECONDS, seconds));
    }

    /**
     * Blank and unparseable both fall back to the default rather than to zero. Zero is a
     * real setting that switches the window off, so it has to be reachable only by somebody
     * actually typing it, never by a missing or broken row.
     */
    private static int window(String v) {
        if (v == null || v.isBlank()) {
            return DEFAULT_SELF_CANCEL_WINDOW_SECONDS;
        }
        try {
            return clampWindow(Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_SELF_CANCEL_WINDOW_SECONDS;
        }
    }
}
