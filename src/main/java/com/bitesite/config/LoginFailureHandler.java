package com.bitesite.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Distinguishes "email not verified yet" (AppUserPrincipal#isAccountNonLocked, thrown as
 * LockedException) from every other login failure, so the login page can point the user
 * at resending the verification email instead of a generic "bad credentials" message.
 */
@Component
@RequiredArgsConstructor
public class LoginFailureHandler implements AuthenticationFailureHandler {

    private final RateLimiter rateLimiter;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        // Only a wrong password is a guess. An unverified or disabled account is not, and
        // counting it would spend a campus's budget on students waiting for an email.
        if (exception instanceof BadCredentialsException) {
            LoginRateLimitFilter.recordFailure(rateLimiter, request);
        }
        String redirect = exception instanceof LockedException ? "/login?error=unverified" : "/login?error";
        response.sendRedirect(request.getContextPath() + redirect);
    }
}
