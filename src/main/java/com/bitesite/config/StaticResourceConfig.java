package com.bitesite.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ResourceUrlEncodingFilter;
import org.springframework.web.servlet.resource.VersionResourceResolver;

import java.time.Duration;

/**
 * How static files are cached and addressed.
 *
 * <p>Before this existed, every asset came back with Spring Security's
 * {@code Cache-Control: no-cache, no-store, max-age=0, must-revalidate}. Right for an
 * authenticated page, wrong for a stylesheet: the browser re-downloaded everything on
 * every navigation — around 4.15MB per page view, nearly all of it the icon font.
 * {@code SecurityConfig.staticResources()} takes these paths out of the filter chain so
 * that header is no longer applied; this class says what should be sent instead.
 *
 * <p>Two policies, because only some of these files can be addressed immutably:
 *
 * <ul>
 *   <li><b>CSS and JS</b> get a content hash in the URL
 *       ({@code /css/parts/02-base-a1b2c3….css}). Thymeleaf's {@code @{...}} rewrites the
 *       links, so a changed file gets a changed URL automatically. That makes the URL a
 *       promise about the bytes, which is what earns {@code immutable} and a year: the
 *       browser never revalidates, and a deploy still cannot serve anything stale.
 *   <li><b>Images and fonts</b> are referenced from places Thymeleaf never rewrites —
 *       {@code manifest.webmanifest}, {@code offline.html}, {@code url()} inside CSS — so
 *       hashing them would break those references. They get 30 days and no
 *       {@code immutable}: still a long cache, but one that repairs itself within a month
 *       if a logo or font is replaced. Marking these immutable for a year is the trap —
 *       it would strand a changed asset on returning devices with no way to recover.
 * </ul>
 *
 * <p>{@code /uploads/**} is deliberately absent. Those are user-uploaded menu photos,
 * they stay inside the security filter chain, and outlet staff replace them often enough
 * that a long cache would show stale food.
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    private static final Duration HASHED = Duration.ofDays(365);
    private static final Duration UNHASHED = Duration.ofDays(30);

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        CacheControl immutableYear = CacheControl.maxAge(HASHED).cachePublic().immutable();

        registry.addResourceHandler("/css/**")
                .addResourceLocations("classpath:/static/css/")
                .setCacheControl(immutableYear)
                .resourceChain(true)
                .addResolver(new VersionResourceResolver().addContentVersionStrategy("/**"));

        registry.addResourceHandler("/js/**")
                .addResourceLocations("classpath:/static/js/")
                .setCacheControl(immutableYear)
                .resourceChain(true)
                .addResolver(new VersionResourceResolver().addContentVersionStrategy("/**"));

        registry.addResourceHandler("/img/**")
                .addResourceLocations("classpath:/static/img/")
                .setCacheControl(CacheControl.maxAge(UNHASHED).cachePublic());

        registry.addResourceHandler("/fonts/**")
                .addResourceLocations("classpath:/static/fonts/")
                .setCacheControl(CacheControl.maxAge(UNHASHED).cachePublic());
    }

    /**
     * Without this, the hashing above would be inert: {@code VersionResourceResolver}
     * teaches the server to <em>serve</em> a hashed URL, and this filter is what makes
     * Thymeleaf's {@code @{...}} actually <em>emit</em> one. Boot registers it
     * automatically only when the chain is switched on through
     * {@code spring.web.resources.chain.*}; the chain is configured here in Java instead,
     * so the filter has to be declared here too.
     */
    @Bean
    public ResourceUrlEncodingFilter resourceUrlEncodingFilter() {
        return new ResourceUrlEncodingFilter();
    }
}
