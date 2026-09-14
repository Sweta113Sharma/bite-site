package com.bitesite.controller;

import com.bitesite.config.TenantContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Duration;

@Controller
public class SiteController {

    @GetMapping(value = "/sw.js", produces = "application/javascript")
    @ResponseBody
    public ResponseEntity<Resource> serviceWorker() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(new ClassPathResource("static/sw.js"));
    }

    @GetMapping(value = "/manifest.webmanifest", produces = "application/manifest+json")
    @ResponseBody
    public ResponseEntity<Resource> webManifest() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(new ClassPathResource("static/manifest.webmanifest"));
    }

    @GetMapping(value = "/offline.html", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<Resource> offline() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(new ClassPathResource("static/offline.html"));
    }

    @GetMapping("/tenant-unavailable")
    public String tenantUnavailable(Model model) {
        model.addAttribute("pageTitle", "Site unavailable");
        model.addAttribute("tenant", TenantContext.get());
        return "error/tenant-unavailable";
    }

    // No method restriction: Spring Security's CSRF-failure handler forwards the
    // *original* request method here (a rejected POST stays a POST across the forward),
    // so a GET-only mapping would turn a CSRF failure into a confusing 405.
    @RequestMapping("/access-denied")
    public String accessDenied(Model model) {
        model.addAttribute("pageTitle", "Access denied");
        return "error/access-denied";
    }
}
