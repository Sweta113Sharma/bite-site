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

    /**
     * The page a QR code points at: "add BiteSite to your home screen".
     *
     * <p>Public, which is the whole point. Every other route to the install sheet is behind
     * a student login — it is included from the student navbar and opens by itself only on
     * four signed-in pages — so there was no URL that could be printed on a poster or a
     * table tent and scanned by someone who does not have an account yet.
     *
     * <p>No model attributes: which of the three states the page shows (installable,
     * iPhone, already installed) is a property of the visitor's browser, not of the server,
     * and app.js already works it out.
     */
    @GetMapping("/install")
    public String install(Model model) {
        model.addAttribute("pageTitle", "Add to home screen");
        return "install";
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
