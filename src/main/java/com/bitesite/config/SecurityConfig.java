package com.bitesite.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final RoleBasedAuthenticationSuccessHandler successHandler;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.ignoringRequestMatchers(
                    new AntPathRequestMatcher("/api/payments/webhook")))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/", "/login", "/tenant-unavailable",
                            "/register/student", "/css/**", "/js/**", "/uploads/**", "/error",
                            "/actuator/health", "/api/payments/webhook").permitAll()
                    .requestMatchers("/admin/**").hasRole("SUPER_ADMIN")
                    .requestMatchers("/techmgr/**").hasRole("TECH_MANAGER")
                    .requestMatchers("/actuator/**").hasAnyRole("SUPER_ADMIN", "TECH_MANAGER")
                    .requestMatchers("/canteen/**", "/api/orders/queue").hasRole("CANTEEN_STAFF")
                    .requestMatchers("/student/**").hasRole("STUDENT")
                    .anyRequest().authenticated())
            .formLogin(form -> form
                    .loginPage("/login")
                    .loginProcessingUrl("/login")
                    .successHandler(successHandler)
                    .failureUrl("/login?error")
                    .permitAll())
            .logout(logout -> logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/login?logout")
                    .permitAll())
            .exceptionHandling(ex -> ex.accessDeniedPage("/access-denied"));

        return http.build();
    }
}
