package com.bitesite.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
public class LoginFailureHandler implements AuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        String redirect = exception instanceof LockedException ? "/login?error=unverified" : "/login?error";
        response.sendRedirect(request.getContextPath() + redirect);
    }
}
