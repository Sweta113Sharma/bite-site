package com.bitesite.service;

import com.bitesite.dao.FcmTokenDao;
import com.bitesite.dao.UserDao;
import com.bitesite.model.FcmToken;
import com.bitesite.model.User;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Sends order alerts to the native Android apps over FCM.
 *
 * <p>This channel exists because the other one cannot reach these apps at all. The apps
 * are WebView shells around the same pages the browser gets, and Android's WebView does
 * not implement the Push API — {@code 'PushManager' in window} is false there, so the
 * VAPID/Web Push path in {@link PushNotificationService} never even registers a
 * subscription. FCM is the only route to a phone running the app.
 *
 * <p>Optional in the same way every other integration in this app is: with no credentials
 * set, {@link #isConfigured()} is false and {@link OrderNotifier} skips the channel. That
 * keeps a developer without a Firebase project running the app normally.
 */
@Service
@Slf4j
public class FcmSender {

    /** Named rather than the default app, so initialising here cannot collide with
     * anything else that reaches for {@code FirebaseApp.getInstance()}. */
    private static final String APP_NAME = "bitesite";

    private final FcmTokenDao fcmTokenDao;
    private final UserDao userDao;
    private final String credentialsJson;
    private final String credentialsPath;

    private volatile FirebaseApp firebaseApp;

    public FcmSender(FcmTokenDao fcmTokenDao, UserDao userDao,
            @Value("${firebase.credentials-json:}") String credentialsJson,
            @Value("${firebase.credentials-path:}") String credentialsPath) {
        this.fcmTokenDao = fcmTokenDao;
        this.userDao = userDao;
        this.credentialsJson = credentialsJson;
        this.credentialsPath = credentialsPath;
    }

    /**
     * Two ways in, because the two environments want different things. Azure App Service
     * has no convenient writable secret file, so the whole service-account JSON goes in
     * one app setting; a developer already holding the file just points at it.
     */
    @PostConstruct
    void init() {
        if (credentialsJson.isBlank() && credentialsPath.isBlank()) {
            log.info("FCM is not configured — the Android apps will not receive order alerts.");
            return;
        }
        try (InputStream in = openCredentials()) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(in))
                    .build();
            // Re-initialising the same name throws, which a Spring context restart in the
            // test suite would otherwise turn into a startup failure.
            this.firebaseApp = FirebaseApp.getApps().stream()
                    .filter(app -> APP_NAME.equals(app.getName()))
                    .findFirst()
                    .orElseGet(() -> FirebaseApp.initializeApp(options, APP_NAME));
            log.info("FCM initialised — the Android apps can receive order alerts.");
        } catch (Exception e) {
            // Deliberately not fatal. A bad key should cost the notification channel, not
            // the ability to take orders.
            log.error("FCM credentials could not be loaded; order alerts to the apps are off", e);
            this.firebaseApp = null;
        }
    }

    private InputStream openCredentials() throws Exception {
        if (!credentialsJson.isBlank()) {
            return new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8));
        }
        return Files.newInputStream(Path.of(credentialsPath));
    }

    public boolean isConfigured() {
        return firebaseApp != null;
    }

    /**
     * Stores a device's token. Deliberately not gated on {@link #isConfigured()}: whether
     * this server can currently send is a separate question from whether the token is
     * worth keeping, and dropping registrations while credentials are being sorted out
     * would mean every app install had to be re-opened afterwards to re-register.
     */
    public void registerToken(Long userId, String token, String platform) {
        fcmTokenDao.save(FcmToken.builder()
                .userId(userId)
                .token(token)
                .platform(platform == null || platform.isBlank() ? "android" : platform)
                .build());
    }

    /**
     * Removes one of the caller's own devices, scoped to them for the same reason the Web
     * Push unsubscribe is: possession of a token string is not proof of owning the device.
     *
     * @return true if a token was actually removed
     */
    public boolean unregisterToken(Long userId, String token) {
        return fcmTokenDao.deleteByTokenForUser(token, userId) > 0;
    }

    /**
     * Best-effort: never throws. An order transition must succeed even when a notification
     * does not, exactly as with Web Push. Runs off the request thread so the order
     * transaction ends at the speed of the database rather than of Google's gateway.
     */
    @Async
    public void notifyUser(Long userId, String title, String body) {
        if (!isConfigured()) {
            return;
        }
        // Same fail-closed check the Web Push path makes, for the same reason: a future
        // caller must not be able to notify someone who opted out by picking the other
        // channel. A missing user counts as opted out.
        if (!userDao.findById(userId).map(User::isNotifyOrderUpdates).orElse(false)) {
            return;
        }
        List<FcmToken> tokens = fcmTokenDao.findByUserId(userId);
        if (tokens.isEmpty()) {
            return;
        }

        FirebaseMessaging messaging = FirebaseMessaging.getInstance(firebaseApp);
        for (FcmToken token : tokens) {
            try {
                messaging.send(Message.builder()
                        .setToken(token.getToken())
                        .setNotification(Notification.builder()
                                .setTitle(title)
                                .setBody(body)
                                .build())
                        .setAndroidConfig(AndroidConfig.builder()
                                // An order going ready is worth waking a dozing phone for;
                                // it is stale within minutes of being sent.
                                .setPriority(AndroidConfig.Priority.HIGH)
                                .setNotification(AndroidNotification.builder()
                                        // Collapses to one line per order rather than
                                        // stacking PAID/PREPARING/READY separately.
                                        .setTag("bitesite-order")
                                        .build())
                                .build())
                        .build());
            } catch (FirebaseMessagingException e) {
                MessagingErrorCode code = e.getMessagingErrorCode();
                if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                    // The app was uninstalled or the token rotated. FCM saying so is
                    // authority enough to drop the row, mirroring the 404/410 handling
                    // on the Web Push side.
                    fcmTokenDao.deleteByToken(token.getToken());
                } else {
                    log.warn("FCM message to user {} failed with {}", userId, code, e);
                }
            } catch (Exception e) {
                log.error("Failed to send an FCM message to user {}", userId, e);
            }
        }
    }
}
