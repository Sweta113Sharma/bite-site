package com.bitesite.controller.api;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.model.User;
import com.bitesite.service.FcmSender;
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
    private final FcmSender fcmSender;

    @GetMapping("/public-key")
    public Map<String, Object> publicKey() {
        return Map.of("configured", pushNotificationService.isConfigured(),
                "publicKey", pushNotificationService.isConfigured() ? pushNotificationService.getPublicKey() : "");
    }

    /**
     * Registers an FCM token from a native app.
     *
     * <p>Separate from {@code /subscribe} because the two channels identify a device
     * differently: Web Push by an endpoint URL and a key pair, FCM by one opaque token.
     * The apps call this instead of {@code /subscribe} because Android's WebView has no
     * Push API for {@code /subscribe} to have produced a subscription with.
     */
    @PostMapping("/fcm-token")
    @ResponseBody
    public Map<String, Boolean> registerFcmToken(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam String token, @RequestParam(required = false) String platform) {
        User user = principal.getUser();
        fcmSender.registerToken(user.getId(), token, platform);
        return Map.of("ok", true);
    }

    /** Drops one of the caller's own device tokens; scoped to them, as unsubscribe is. */
    @PostMapping("/fcm-token/remove")
    @ResponseBody
    public Map<String, Boolean> removeFcmToken(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam String token) {
        User user = principal.getUser();
        boolean removed = fcmSender.unregisterToken(user.getId(), token);
        return Map.of("ok", true, "removed", removed);
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
