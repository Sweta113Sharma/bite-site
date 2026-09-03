package com.bitesite.config;

import lombok.RequiredArgsConstructor;
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
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final RoleBasedAuthenticationSuccessHandler successHandler;
    private final LoginFailureHandler failureHandler;
    private final PortalGateFilter portalGateFilter;

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
        return web -> web.ignoring().requestMatchers("/css/**", "/js/**", "/img/**", "/fonts/**");
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
                    new AntPathRequestMatcher("/api/payments/webhook")))
            // Portal gate filter runs after authentication but before authorization
            .addFilterAfter(portalGateFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/", "/login", "/tenant-unavailable",
                            "/register/student", "/css/**", "/js/**", "/img/**", "/fonts/**", "/uploads/**", "/error",
                            "/actuator/health", "/api/payments/webhook",
                            "/privacy-policy", "/terms", "/refund-policy",
                            "/shipping-policy", "/grievance-policy",
                            "/verify", "/verify/**", "/resend-verification",
                            // Recovery has to be reachable by someone who cannot sign in —
                            // that is the entire situation it exists for.
                            "/forgot-password", "/reset-password",
                            "/manifest.webmanifest", "/sw.js", "/offline.html").permitAll()
                    // Admin portal routes — any admin-portal role
                    .requestMatchers("/admin/**").hasAnyRole("SUPER_ADMIN", "TECH_MANAGER")
                    .requestMatchers("/techmgr/**").hasAnyRole("SUPER_ADMIN", "TECH_MANAGER")
                    .requestMatchers("/actuator/**").hasAnyRole("SUPER_ADMIN", "TECH_MANAGER")
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
            .logout(logout -> logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/login?logout")
                    .permitAll())
            .exceptionHandling(ex -> ex.accessDeniedPage("/access-denied"));

        return http.build();
    }
}
