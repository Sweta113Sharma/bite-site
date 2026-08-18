package com.bitesite.service;

import com.bitesite.dao.PushSubscriptionDao;
import com.bitesite.model.PushSubscription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.Security;

/**
 * VAPID keys are blank until generated (see README) — while blank, isConfigured() is false
 * and callers skip sending, mirroring every other optional integration in this app
 * (SmtpEmailService, TwilioSmsService, RazorpayPaymentGateway).
 */
@Service
@Slf4j
public class PushNotificationService {

    static {
        // web-push needs BouncyCastle registered as a JCE provider for the EC crypto —
        // registering once here rather than per-call.
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        }
    }

    private final PushSubscriptionDao pushSubscriptionDao;
    private final String publicKey;
    private final String privateKey;
    private final String subject;

    public PushNotificationService(PushSubscriptionDao pushSubscriptionDao,
            @Value("${vapid.public-key}") String publicKey,
            @Value("${vapid.private-key}") String privateKey,
            @Value("${vapid.subject}") String subject) {
        this.pushSubscriptionDao = pushSubscriptionDao;
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.subject = subject;
    }

    public boolean isConfigured() {
        return !publicKey.isBlank() && !privateKey.isBlank();
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void subscribe(Long userId, String endpoint, String p256dhKey, String authKey) {
        pushSubscriptionDao.save(PushSubscription.builder()
                .userId(userId).endpoint(endpoint).p256dhKey(p256dhKey).authKey(authKey).build());
    }

    public void unsubscribe(String endpoint) {
        pushSubscriptionDao.deleteByEndpoint(endpoint);
    }

    /** Best-effort: never throws. Order-status transitions must succeed even if a push
     * fails to send (dead subscription, gateway hiccup) — this is a nice-to-have alert,
     * not a step in the order flow itself. A 404/410 response means the browser dropped
     * the subscription, so we drop it too rather than retry it forever. */
    public void notifyUser(Long userId, String title, String body) {
        if (!isConfigured()) {
            return;
        }
        for (PushSubscription sub : pushSubscriptionDao.findByUserId(userId)) {
            try {
                PushService pushService = new PushService();
                pushService.setSubject(subject);
                pushService.setPublicKey(publicKey);
                pushService.setPrivateKey(privateKey);

                String payload = "{\"title\":" + jsonString(title) + ",\"body\":" + jsonString(body) + "}";
                Notification notification = Notification.builder()
                        .endpoint(sub.getEndpoint())
                        .userPublicKey(sub.getP256dhKey())
                        .userAuth(sub.getAuthKey())
                        .payload(payload)
                        .build();

                HttpResponse response = pushService.send(notification);
                int status = response.getStatusLine().getStatusCode();
                if (status == 404 || status == 410) {
                    pushSubscriptionDao.deleteByEndpoint(sub.getEndpoint());
                } else if (status >= 300) {
                    log.warn("Push notification to user {} got HTTP {}", userId, status);
                }
            } catch (Exception e) {
                log.error("Failed to send push notification to user {}", userId, e);
            }
        }
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
