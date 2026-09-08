package com.bitesite.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Whether the request came from a phone-shaped client.
 *
 * <p>Used to decide who sees the welcome screen at {@code /}. It is a marketing page laid
 * out as an app screen, and on a wide desktop window it reads as a phone column stranded
 * in empty space, so desktop visitors go straight to the sign-in form instead.
 *
 * <p>Prefers {@code Sec-CH-UA-Mobile} over sniffing the User-Agent string. That header is
 * a client hint whose entire meaning is this question, it is boolean rather than a pattern
 * match against a string vendors deliberately make misleading, and Chromium sends it by
 * default. That covers the case that matters most here: both Android apps are Capacitor
 * WebViews, so they are Chromium and always send it.
 *
 * <p>Safari and Firefox send no such hint, hence the User-Agent fallback. Getting this
 * wrong is cheap in both directions: the worst outcome is a visitor seeing the sign-in
 * form when they might have liked the intro, or the intro when they wanted the form, and
 * every route out of either is one tap away. It is deliberately not used for anything
 * that decides access or correctness.
 */
public final class MobileClient {

    private MobileClient() {}

    public static boolean isMobile(HttpServletRequest request) {
        // "?1" mobile, "?0" not. Authoritative when present: the browser is answering the
        // question directly rather than us inferring it.
        String hint = request.getHeader("Sec-CH-UA-Mobile");
        if (hint != null) {
            return hint.contains("?1");
        }

        String ua = request.getHeader("User-Agent");
        if (ua == null) {
            // No User-Agent at all is a script or a health check, not a phone. Falling back
            // to the sign-in form keeps the cheaper page as the default for non-browsers.
            return false;
        }
        // "Mobi" is the token the spec tells mobile browsers to carry, and it catches
        // Android Chrome ("Mobile Safari") and iOS ("Mobile/15E148") alike. iPadOS
        // deliberately reports itself as a Mac and is therefore treated as desktop, which
        // is the right answer for a tablet-sized window anyway.
        return ua.contains("Mobi") || ua.contains("Android") || ua.contains("iPhone");
    }
}
