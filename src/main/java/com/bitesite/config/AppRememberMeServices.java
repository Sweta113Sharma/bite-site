package com.bitesite.config;

import com.bitesite.model.PortalTarget;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.session.security.web.authentication.SpringSessionRememberMeServices;
import org.springframework.stereotype.Component;

/**
 * Keeps a sign-in from the Android apps alive after the app is closed.
 *
 * <p>Both apps are Capacitor shells, and Capacitor registers its {@code CapacitorCookies}
 * plugin whether or not it is enabled. That plugin calls
 * {@code CookieManager.removeSessionCookies()} when the app starts and again when its
 * activity is destroyed. The SESSION cookie had no expiry, which is exactly what makes a
 * cookie a session cookie, so every cold start of either app was a sign-out. The
 * 30-minute idle timeout would have ended the session soon after anyway.
 *
 * <p>This issues no second token. When a login asks to be remembered, Spring Session
 * stretches that one session's idle timeout to 30 days, and Boot's session
 * auto-configuration writes the SESSION cookie with a Max-Age. So everything that ends a
 * session still ends this one, because it is the same row in SPRING_SESSION: signing
 * out, a password change or reset, an email change, "sign out other devices", and the
 * account being switched off ({@link UserSessionRegistry}). Anonymous visitors and
 * browser sign-ins keep the 30 minutes.
 *
 * <p>The login page asks only inside the Android apps and the site installed to a home
 * screen (initKeptSignIn in app.js); a browser tab does not. Never honoured
 * on the admin portal: a platform account reaches every college, and a month-long session
 * there is not a trade this product needs to make.
 */
@Component
@RequiredArgsConstructor
public class AppRememberMeServices extends SpringSessionRememberMeServices {

    private final PortalResolver portalResolver;

    @Override
    protected boolean rememberMeRequested(HttpServletRequest request, String parameter) {
        return portalResolver.resolve(request) != PortalTarget.ADMIN
                && super.rememberMeRequested(request, parameter);
    }
}
