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

    @PostMapping("/unsubscribe")
    @ResponseBody
    public Map<String, Boolean> unsubscribe(@RequestParam String endpoint) {
        pushNotificationService.unsubscribe(endpoint);
        return Map.of("ok", true);
    }
}
