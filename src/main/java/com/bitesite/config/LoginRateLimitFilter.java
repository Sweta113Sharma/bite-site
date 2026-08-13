package com.bitesite.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Throttles login attempts per source IP before they ever reach Spring Security's
 * authentication filter — deliberately IP-keyed rather than per-account, so an attacker
 * can't use it to lock a real student out of their own account by hammering their email.
 */
@RequiredArgsConstructor
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_ATTEMPTS = 10;
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private final RateLimiter rateLimiter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(request.getMethod()) && "/login".equals(request.getRequestURI())) {
            String key = "login:" + request.getRemoteAddr();
            if (!rateLimiter.tryConsume(key, MAX_ATTEMPTS, WINDOW)) {
                response.sendRedirect(request.getContextPath() + "/login?error=ratelimit");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
