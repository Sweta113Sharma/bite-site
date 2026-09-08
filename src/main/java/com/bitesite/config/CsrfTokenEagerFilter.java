package com.bitesite.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Resolves the CSRF token before anything renders.
 *
 * <p>Spring Security 6 defers token generation: {@code _csrf} is a supplier, and the token
 * is only created — and saved into the session — when something reads it. The only thing
 * that reads it on most pages is {@code fragments/head}, which puts it in a meta tag for
 * the JavaScript to post with. That read happens during template rendering, by which point
 * a large page may already have flushed its first buffer, and creating a session against a
 * committed response throws {@code IllegalStateException: Cannot create a session after the
 * response has been committed}.
 *
 * <p>Until now that never fired, because {@code GlobalModelAttributes.cartItemCount()}
 * touched the session-scoped Cart on every single request and created the session as a side
 * effect, well before rendering. That made an unrelated read load-bearing for CSRF: guarding
 * the cart read by role — which is worth doing, since it puts a serialized-object write on
 * every page of every portal — immediately broke token generation on 23 tests.
 *
 * <p>This filter makes the ordering explicit instead of incidental. It is the remedy
 * Spring Security's own documentation gives for the deferred-token problem.
 *
 * <p>Static assets never reach here: {@code /css}, {@code /js}, {@code /img} and
 * {@code /fonts} are excluded from the security filter chain entirely (see
 * {@code SecurityConfig#webSecurityCustomizer}), so this cannot start a session for an
 * image request.
 */
@Component
public class CsrfTokenEagerFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) {
            // Reading the value is what forces generation and the save. The result is
            // deliberately unused.
            token.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
