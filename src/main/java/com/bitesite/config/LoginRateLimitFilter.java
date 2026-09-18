package com.bitesite.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Throttles password guessing on {@code POST /login}, counting only wrong passwords.
 *
 * <p>It used to count every attempt per source IP, successes included, at 10 per five
 * minutes. Campus Wi-Fi and Indian mobile carriers put hundreds of students behind one
 * public address, so the eleventh student on a college network to sign in inside five
 * minutes was refused with the right password (reproduced locally, 2026-09-18).
 *
 * <p>Now two budgets, both counted by {@link LoginFailureHandler} and only for wrong
 * passwords, and both checked here before the attempt:
 * <ul>
 *   <li><b>address + account</b>, 10 per window: guessing one student's password from one
 *       place stops fast, and nobody else on that network is affected.
 *   <li><b>address</b>, 100 per window: trying one password across many accounts from one
 *       place still stops, with room for a whole campus mistyping at lunch.
 * </ul>
 * Still never keyed on the account alone, so hammering someone's email cannot lock the
 * real student out of their own account from their own network. The email is hashed in
 * the key: the table is a counter, not a record of who tried to sign in.
 */
@RequiredArgsConstructor
public class LoginRateLimitFilter extends OncePerRequestFilter {
    static final int MAX_FAILURES_PER_ACCOUNT = 10;
    static final int MAX_FAILURES_PER_ADDRESS = 100;
    static final Duration WINDOW = Duration.ofMinutes(5);

    private final RateLimiter rateLimiter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(request.getMethod()) && "/login".equals(request.getRequestURI())) {
            String ip = request.getRemoteAddr();
            if (rateLimiter.isBlocked(accountKey(ip, request.getParameter("username")), MAX_FAILURES_PER_ACCOUNT, WINDOW)
                    || rateLimiter.isBlocked(addressKey(ip), MAX_FAILURES_PER_ADDRESS, WINDOW)) {
                response.sendRedirect(request.getContextPath() + "/login?error=ratelimit");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** Counts one wrong password against both budgets. Called by {@link LoginFailureHandler}. */
    static void recordFailure(RateLimiter rateLimiter, HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        rateLimiter.tryConsume(accountKey(ip, request.getParameter("username")), MAX_FAILURES_PER_ACCOUNT, WINDOW);
        rateLimiter.tryConsume(addressKey(ip), MAX_FAILURES_PER_ADDRESS, WINDOW);
    }

    static String addressKey(String ip) {
        return "login-fail:" + ip;
    }

    static String accountKey(String ip, String username) {
        String email = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(email.getBytes(StandardCharsets.UTF_8));
            return "login-fail:" + ip + ":" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
