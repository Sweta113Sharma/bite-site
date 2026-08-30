package com.bitesite.controller.api;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.model.User;
import com.bitesite.service.PushNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/push")
@RequiredArgsConstructor
public class PushSubscriptionController {

    private final PushNotificationService pushNotificationService;

    @GetMapping("/public-key")
    public Map<String, Object> publicKey() {
        return Map.of("configured", pushNotificationService.isConfigured(),
                "publicKey", pushNotificationService.isConfigured() ? pushNotificationService.getPublicKey() : "");
    }

    @PostMapping("/subscribe")
    @ResponseBody
    public Map<String, Boolean> subscribe(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam String endpoint, @RequestParam String p256dh, @RequestParam String auth) {
        User user = principal.getUser();
        pushNotificationService.subscribe(user.getId(), endpoint, p256dh, auth);
        return Map.of("ok", true);
    }

    /**
     * Unsubscribes one of the caller's own devices. The endpoint alone is not proof of
     * ownership — it is handed to whatever server the page posts to — so the delete is
     * scoped to the authenticated user. An endpoint belonging to someone else simply
     * removes nothing rather than silencing their notifications.
     */
    @PostMapping("/unsubscribe")
    @ResponseBody
    public Map<String, Boolean> unsubscribe(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam String endpoint) {
        User user = principal.getUser();
        boolean removed = pushNotificationService.unsubscribe(user.getId(), endpoint);
        // Reported honestly so the client can tell "you are unsubscribed" from "that was
        // not yours"; the browser-side toggle only ever sends its own endpoint.
        return Map.of("ok", true, "removed", removed);
    }
}
