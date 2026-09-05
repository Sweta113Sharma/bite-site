package com.bitesite.service;

import com.bitesite.dao.UserDao;
import com.bitesite.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Tells a student what happened to their order.
 *
 * <p>Push only, deliberately. Order updates are the highest-volume message this product
 * sends — one or more per order, every lunchtime — and the mail plan is 300 messages a day
 * shared with every verification code, password reset and sign-in code in the product.
 * Order email would have spent that allowance on notifications and left people unable to
 * sign in, so it is not sent at all rather than sent and rationed. Web push costs nothing
 * per message, and a native app with its own notifications is the intended long-term
 * channel.
 *
 * <p>This class stays as the one place order events fan out from, even with a single
 * channel behind it: the native app becomes another line here rather than a fourth edit to
 * {@code OrderService}, and the opt-out below keeps governing every channel at once.
 *
 * <p>Consequence worth being clear about: a student who has not granted push permission
 * is told nothing outside the app, and finds out an order is ready by looking. The order
 * strip on every customer page polls for exactly that reason.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderNotifier {

    private final UserDao userDao;
    private final PushNotificationService pushNotificationService;
    private final FcmSender fcmSender;

    public void notifyOrderUpdate(Long userId, String subject, String body) {
        User user = userDao.findById(userId).orElse(null);
        if (user == null) {
            // Failing closed: a message with no recipient is not worth guessing at.
            log.warn("No user {} to notify about an order update", userId);
            return;
        }
        // One opt-out governs every channel. It already existed and already gated push;
        // keeping the check here is what will make it still mean "everything" once the
        // native app is a second channel.
        if (!user.isNotifyOrderUpdates()) {
            return;
        }

        // Routed through PushNotificationService rather than inlined so its subscription
        // handling — including dropping subscriptions the browser has discarded — stays in
        // one place. It re-checks the preference, harmlessly.
        pushNotificationService.notifyUser(userId, subject, body);

        // The second channel this class was written to expect. Not an alternative to the
        // line above but a complement: Web Push cannot reach the Android apps at all,
        // because their WebView has no Push API, and FCM cannot reach a desktop browser.
        // Someone signed in on both holds a row in each table and should hear about the
        // order in both places, so both are sent and neither is a duplicate of the other.
        fcmSender.notifyUser(userId, subject, body);
    }
}
