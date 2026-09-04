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

import java.time.Duration;

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

    private final RateLimiter rateLimiter;

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
            log.warn("CSP would have blocked: directive={} blockedUri={} documentUri={}",
                    report.optString("violated-directive", "?"),
                    report.optString("blocked-uri", "?"),
                    report.optString("document-uri", "?"));
        } catch (Exception e) {
            log.debug("Unparseable CSP report ignored");
        }
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
