package com.bitesite.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * Everything the app is known to load, plus a report endpoint.
     *
     * <p>{@code frame-ancestors 'none'} restates X-Frame-Options: DENY for browsers that
     * prefer the CSP form, and {@code object-src 'none'} plus {@code base-uri 'self'}
     * close two injection routes that cost nothing to shut.
     */
    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            // jsDelivr, fonts.googleapis.com and fonts.gstatic.com are gone from all three
            // of these: Bootstrap and Archivo are served from our own origin now, so the
            // only third party left anywhere in the policy is Razorpay, which has to be.
            "script-src 'self' 'unsafe-inline' "
                    + "https://checkout.razorpay.com https://cdn.razorpay.com",
            "style-src 'self' 'unsafe-inline'",
            "font-src 'self' data:",
            "img-src 'self' data: blob: https://res.cloudinary.com",
            "connect-src 'self' https://api.razorpay.com https://lumberjack.razorpay.com",
            "frame-src https://api.razorpay.com https://checkout.razorpay.com",
            "form-action 'self'",
            "base-uri 'self'",
            "object-src 'none'",
            "frame-ancestors 'none'",
            "report-uri /api/csp-report");

    /** Flip to true only once the reports have been quiet on real traffic. */
    @Value("${app.security.csp-enforce:false}")
    private boolean cspEnforce;

    private final RoleBasedAuthenticationSuccessHandler successHandler;
    private final LoginFailureHandler failureHandler;
    private final PortalGateFilter portalGateFilter;
    private final CsrfTokenEagerFilter csrfTokenEagerFilter;
    private final AppRememberMeServices rememberMeServices;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Takes static assets out of the security filter chain entirely.
     *
     * <p>This is a caching fix, not a performance micro-optimisation. Spring Security's
     * header writer stamps {@code Cache-Control: no-cache, no-store, max-age=0,
     * must-revalidate} on every response it handles — correct for an authenticated page,
     * badly wrong for a stylesheet. With these paths inside the chain the browser was
     * re-downloading every asset on every navigation, including the 4MB icon font: about
     * 4.15MB per page view for a student on campus wifi.
     *
     * <p>Safe because all four directories are already {@code permitAll} below and hold
     * nothing user-specific. {@code /uploads/**} is deliberately NOT here — those are
     * uploaded files and stay behind the chain.
     */
    @Bean
    public WebSecurityCustomizer staticResources() {
        return web -> web.ignoring().requestMatchers(
                "/css/**", "/js/**", "/img/**", "/fonts/**",
                "/sw.js", "/manifest.webmanifest", "/offline.html"
        );
    }

    /**
     * Exposed explicitly (rather than relying on Spring Security's internal default) so
     * {@link RoleBasedAuthenticationSuccessHandler} and {@code RoleSwitchController} can
     * inject the exact same repository the filter chain reads from on every request, and
     * call {@code saveContext(...)} on it directly. Without that explicit save, updating
     * {@code SecurityContextHolder} mid-request only affects the current request/response
     * — {@code SecurityContextHolderFilter} (Spring Security 6's default) loads the context
     * at the start of a request but does not automatically persist further changes back to
     * the session the way the older {@code SecurityContextPersistenceFilter} did.
     */
    // static: SecurityConfig's own constructor needs RoleBasedAuthenticationSuccessHandler,
    // which in turn needs this bean — a non-static @Bean method here would create a cycle
    // (Spring can't finish constructing SecurityConfig to obtain this bean from it, because
    // finishing SecurityConfig's construction is exactly what's waiting on it). A static
    // @Bean method sidesteps that: Spring can invoke it without an instance of SecurityConfig.
    @Bean
    public static SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository securityContextRepository)
            throws Exception {
        http
            .securityContext(context -> context.securityContextRepository(securityContextRepository))
            .csrf(csrf -> csrf.ignoringRequestMatchers(
                    new AntPathRequestMatcher("/api/payments/webhook"),
                    // The browser posts violation reports itself, with no session and no
                    // token. It is write-only, stores nothing, and is rate limited.
                    new AntPathRequestMatcher("/api/csp-report")))
            // Portal gate filter runs after authentication but before authorization
            // Before anything renders, so token generation never races a committed
            // response. See CsrfTokenEagerFilter for why this used to work by accident.
            .addFilterAfter(csrfTokenEagerFilter, CsrfFilter.class)
            .addFilterAfter(portalGateFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/", "/login", "/tenant-unavailable",
                            "/register/student", "/css/**", "/js/**", "/img/**", "/fonts/**", "/uploads/**", "/error",
                            "/actuator/health", "/actuator/health/liveness", "/api/payments/webhook", "/api/csp-report",
                            "/privacy-policy", "/account-deletion", "/terms", "/refund-policy",
                            "/shipping-policy", "/grievance-policy",
                            "/verify", "/verify/**", "/resend-verification",
                            // Recovery has to be reachable by someone who cannot sign in —
                            // that is the entire situation it exists for.
                            "/forgot-password", "/reset-password",
                            // Second half of a platform sign-in. Reachable without being
                            // authenticated, because by definition you are not yet.
                            "/login/verify",
                            "/manifest.webmanifest", "/sw.js", "/offline.html",
                            // The QR-code landing page. Public by design: the person it is
                            // aimed at is standing at a canteen counter without an account.
                            "/install").permitAll()
                    // Admin portal routes — any admin-portal role
                    .requestMatchers("/admin/**").hasAnyRole("SUPER_ADMIN", "ADMIN", "TECH_MANAGER")
                    .requestMatchers("/techmgr/**").hasAnyRole("SUPER_ADMIN", "ADMIN", "TECH_MANAGER")
                    .requestMatchers("/actuator/**").hasAnyRole("SUPER_ADMIN", "ADMIN", "TECH_MANAGER")
                    // Outlet portal routes. Coarse on purpose — this rule says only which
                    // roles may enter the portal. Manager-vs-operator capability is
                    // enforced per method with PortalGuard + StaffScope, because some
                    // endpoints under /canteen/menu (the stock toggles) are deliberately
                    // shared and a second URL rule could not express that without
                    // contradicting this one.
                    .requestMatchers("/canteen/**", "/api/orders/queue")
                            .hasAnyRole("CANTEEN_MANAGER", "CANTEEN_OPERATOR")
                    // App portal routes (was STUDENT, now USER)
                    .requestMatchers("/student/**").hasRole("USER")
                    // Role switching — any authenticated user
                    .requestMatchers("/api/role/switch").authenticated()
                    .anyRequest().authenticated())
            .formLogin(form -> form
                    .loginPage("/login")
                    .loginProcessingUrl("/login")
                    .successHandler(successHandler)
                    .failureHandler(failureHandler)
                    .permitAll())
            // Lengthens the session a login asked to keep, rather than issuing a token of
            // its own. See AppRememberMeServices for why the apps need it.
            .rememberMe(rememberMe -> rememberMe.rememberMeServices(rememberMeServices))
            .logout(logout -> logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/login?logout")
                    .permitAll())
            .headers(headers -> headers
                    /*
                     * Spring Security already sends nosniff and X-Frame-Options: DENY, and
                     * the platform adds HSTS. These are the two it does not.
                     *
                     * Referrer-Policy matters more here than it looks. Every page a student
                     * pays from loads Razorpay's checkout script, and the pages either side
                     * of it have order ids in the path. Without a policy the browser sends
                     * the full URL as the Referer to every third party on the page — the
                     * CDN, the font host, the payment gateway — handing them a running log
                     * of which student looked at which order. strict-origin-when-cross-origin
                     * keeps the full path for our own requests and sends only the origin
                     * outward, which is what those third parties actually need.
                     */
                    .referrerPolicy(referrer -> referrer.policy(
                            ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                    /*
                     * Switches off device APIs this product never asks for, so a script that
                     * does get injected cannot reach for them either. Deliberately silent on
                     * `payment`: Razorpay's checkout may use the Payment Request API, and
                     * denying it here would break the till to defend against nothing.
                     */
                    /*
                     * Report-only, and deliberately so. Razorpay does not publish a
                     * definitive list of the origins its checkout reaches, and this app
                     * also pulls Bootstrap from a CDN, Google Fonts, and inline script
                     * that Thymeleaf generates. A policy written from the outside and
                     * enforced would break the till in production, and you would hear
                     * about it from students rather than from a test.
                     *
                     * So the browser reports what this policy WOULD have blocked, nobody
                     * is affected, and the reports say what to fix. Once /api/csp-report
                     * has been quiet for a few days on real traffic, set
                     * CSP_ENFORCE=true — the policy string is shared, so what gets
                     * enforced is exactly what was observed to be clean.
                     *
                     * unsafe-inline is present for now because the templates carry inline
                     * script and style. Nonces are the proper fix and the reports will
                     * show how much is actually involved before that work is scoped.
                     */
                    .contentSecurityPolicy(csp -> {
                        csp.policyDirectives(CONTENT_SECURITY_POLICY);
                        if (!cspEnforce) {
                            csp.reportOnly();
                        }
                    })
                    .permissionsPolicyHeader(permissions -> permissions.policy(
                            "camera=(), microphone=(), geolocation=(), usb=(), magnetometer=(), "
                                    + "accelerometer=(), gyroscope=(), interest-cohort=()"))
                    /*
                     * Spring Security's default is `no-cache, no-store, max-age=0,
                     * must-revalidate` plus Pragma and Expires. The `no-store` in there is
                     * the reason pressing Back felt like opening the app again: a response
                     * carrying it is refused entry to the browser's back/forward cache, so
                     * every Back was a full navigation — server round trip, re-render,
                     * re-decode every photo — where a bfcache restore is near-instant and
                     * keeps scroll position.
                     *
                     * What replaces it keeps the part that matters. `no-cache` does NOT mean
                     * "do not cache"; it means "cache, but revalidate before reuse", so an
                     * ordinary navigation still asks the server and a logged-out student
                     * still gets the login page. `private` keeps shared proxies out of it.
                     * Dropping only `no-store` is therefore the narrowest change that
                     * unlocks the restore.
                     *
                     * The tradeoff this leaves is real and is handled elsewhere: a restored
                     * page is served from memory WITHOUT asking the server, so on a shared
                     * phone Back could show a screen belonging to someone who has since
                     * logged out. app.js closes that by revalidating on pageshow when the
                     * page came from bfcache — see the `pageshow` handler there, and
                     * SessionApiController which it calls.
                     *
                     * Static assets are unaffected: they were taken out of this filter
                     * chain entirely (see staticResources() above) and must stay out.
                     */
                    .cacheControl(cache -> cache.disable())
                    .addHeaderWriter((request, response) -> {
                        /*
                         * Uploaded images are the one thing in this chain that is safe to
                         * cache forever, and they were the most expensive thing in it not
                         * to: every canteen logo, menu photo and category image was being
                         * revalidated on every navigation.
                         *
                         * Safe because the URL is content-identity. Every write goes
                         * through FileStorageService, which names files
                         * `<prefix>-<random UUID>.webp` — replacing an item's photo calls
                         * storeMenuItemPhoto again and produces a NEW name, and the page
                         * then points at the new URL. An old URL can therefore never
                         * return different bytes, so `immutable` cannot strand anyone on a
                         * stale picture. (StaticResourceConfig used to say the opposite —
                         * that staff replace photos too often to cache them. That reason
                         * predates the UUID naming and no longer holds; the note there has
                         * been corrected.)
                         *
                         * They stay INSIDE this filter chain rather than being moved out
                         * alongside /css and /js, because the chain is what stamps
                         * X-Content-Type-Options: nosniff — which matters more on bytes a
                         * canteen uploaded than on our own stylesheets.
                         *
                         * `public` is honest here: these are already permitAll and shown
                         * to anyone who opens a menu. Nothing user-private is served from
                         * /uploads — it holds exactly two directories, logos and
                         * menu-photos.
                         */
                        String path = request.getRequestURI();
                        String context = request.getContextPath();
                        if (context != null && !context.isEmpty() && path.startsWith(context)) {
                            path = path.substring(context.length());
                        }
                        if (path.startsWith("/uploads/")) {
                            response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
                            // Left over from the default writer otherwise, and a stale
                            // Pragma/Expires beside a year-long max-age is the kind of
                            // contradiction an intermediary resolves the cautious way.
                            response.setHeader("Pragma", "");
                            response.setHeader("Expires", "");
                            return;
                        }
                        response.setHeader("Cache-Control", "no-cache, must-revalidate, private");
                        // Pragma is HTTP/1.0 and only meaningful on requests, but Spring's
                        // default writer sets it and some intermediaries still read it as
                        // "do not store". Set explicitly rather than left to chance.
                        response.setHeader("Pragma", "no-cache");
                        response.setHeader("Expires", "0");
                    }))
            .exceptionHandling(ex -> ex.accessDeniedPage("/access-denied"));

        return http.build();
    }
}
