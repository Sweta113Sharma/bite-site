package com.bitesite.controller.api;

import com.bitesite.config.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/**
 * Where the browser says what the policy would have blocked.
 *
 * <p>The policy ships in report-only mode because nobody can write a correct one for this
 * app from the outside: it loads Bootstrap from a CDN, Google Fonts, Razorpay's checkout
 * and inline Thymeleaf script, and Razorpay does not publish a definitive list of the
 * origins its checkout reaches. Guessing and enforcing would break payments in production
 * and you would hear about it from students. So the browser reports for a while, the
 * reports get read, and the policy is tightened against evidence before anything is
 * enforced.
 *
 * <p>Unauthenticated and CSRF-exempt by necessity — the browser posts these on its own,
 * with no session and no token. That makes it an open write endpoint, so it is capped:
 * the reports are logged, nothing is stored, and the volume is bounded so a script cannot
 * turn the log into a disk-fill.
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CspReportController {

    /** Enough to characterise a real problem, far too few to bury the log. A genuine
     * policy error produces the same handful of violations over and over. */
    private static final int MAX_REPORTS_PER_WINDOW = 100;
    private static final Duration WINDOW = Duration.ofMinutes(10);

    /** A malformed or oversized body is not worth parsing. */
    private static final int MAX_BODY = 8_000;

    /**
     * Hosts this app actually loads from, taken from the policy in SecurityConfig. A
     * violation naming one of these is a real gap that would break the app the day the
     * policy is enforced, and is what this endpoint exists to catch.
     *
     * <p>Anything else is somebody's browser extension injecting into the page. On one day
     * in production that was 258 identical reports for a font from an AI assistant's CDN —
     * the only WARN in the log, and enough to bury a genuine violation. Those still get
     * recorded, at debug, because they say nothing about a policy we can change.
     */
    private static final Set<String> OWN_ORIGINS = Set.of(
            "cdn.jsdelivr.net", "fonts.googleapis.com", "fonts.gstatic.com",
            "checkout.razorpay.com", "cdn.razorpay.com", "api.razorpay.com",
            "lumberjack.razorpay.com", "res.cloudinary.com");

    /** Keywords the browser reports instead of a URL, all of which are about our own page. */
    private static final Set<String> OWN_KEYWORDS = Set.of("self", "inline", "eval", "data", "blob");

    private final RateLimiter rateLimiter;

    /** The host of a blocked URI, or the keyword the browser sent instead of one. */
    private static String hostOf(String blockedUri) {
        if (blockedUri == null || blockedUri.isBlank()) {
            return "?";
        }
        try {
            String host = URI.create(blockedUri).getHost();
            return host == null ? blockedUri : host;
        } catch (IllegalArgumentException e) {
            // Browsers send bare keywords here as often as URLs.
            return blockedUri;
        }
    }

    private static boolean isOurs(String blockedUri) {
        String host = hostOf(blockedUri);
        return OWN_ORIGINS.contains(host) || OWN_KEYWORDS.contains(host);
    }

    @PostMapping(value = "/csp-report", consumes = "*/*")
    public ResponseEntity<Void> report(@RequestBody(required = false) String payload) {
        // 204 regardless: the browser has nothing useful to do with an error here, and
        // saying "rejected" would only invite retries.
        if (payload == null || payload.length() > MAX_BODY) {
            return ResponseEntity.noContent().build();
        }
        if (!rateLimiter.tryConsume("csp-report", MAX_REPORTS_PER_WINDOW, WINDOW)) {
            return ResponseEntity.noContent().build();
        }
        try {
            JSONObject body = new JSONObject(payload);
            JSONObject report = body.optJSONObject("csp-report");
            if (report == null) {
                return ResponseEntity.noContent().build();
            }
            // Only the fields that say what to change. The full report echoes back page
            // URLs, and those carry order ids.
            String directive = report.optString("violated-directive", "?");
            String blockedUri = report.optString("blocked-uri", "?");
            String documentUri = report.optString("document-uri", "?");

            // One line per distinct violation per window. A real policy gap repeats
            // identically on every page load, so the tenth thousand copy of it says
            // nothing the first did not.
            if (!rateLimiter.tryConsume("csp-seen:" + directive + "|" + hostOf(blockedUri), 1, WINDOW)) {
                return ResponseEntity.noContent().build();
            }

            if (isOurs(blockedUri)) {
                log.warn("CSP would have blocked: directive={} blockedUri={} documentUri={}",
                        directive, blockedUri, documentUri);
            } else {
                // Not ours to fix — an extension, or someone probing. Kept, quietly.
                log.debug("CSP report from a third-party origin: directive={} blockedUri={}",
                        directive, blockedUri);
            }
        } catch (Exception e) {
            log.debug("Unparseable CSP report ignored");
        }
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
