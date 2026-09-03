package com.bitesite.service;

import com.bitesite.dao.UserDao;
import com.bitesite.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Tells a student what happened to their order, over every channel that is actually
 * available.
 *
 * <p>Until now this was push only, and push is configured with a VAPID key pair that was
 * never generated — so {@code PushNotificationService.isConfigured()} was false in
 * production and the honest description of the product was that <em>nobody was told their
 * order was ready</em>. A student had to keep the page open and watch the order strip. For
 * an order-ahead product that is close to the whole proposition.
 *
 * <p>Email is the channel that always reaches someone: no permission prompt, no
 * subscription to expire, and it doubles as the receipt the app otherwise keeps only
 * inside itself. Push is kept because it is instant and free, and because a phone in a
 * pocket beats an inbox for "your food is ready right now". SMS is deliberately absent —
 * it is the one channel here with a per-message cost.
 *
 * <p>One opt-out governs both. {@code notify_order_updates} already existed and already
 * gated push; honouring it here means turning it off in Privacy &amp; data genuinely stops
 * everything, rather than stopping the channel the user happened to know about.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderNotifier {

    private final UserDao userDao;
    private final PushNotificationService pushNotificationService;
    private final EmailService emailService;

    /**
     * Sends one message over every configured channel.
     *
     * <p>Both sends are {@code @Async} in their own services, so this returns without
     * waiting on a push gateway or an SMTP handshake — which matters because every caller
     * is inside an order transaction.
     */
    public void notifyOrderUpdate(Long userId, String subject, String body) {
        User user = userDao.findById(userId).orElse(null);
        if (user == null) {
            // Failing closed: a message with no recipient is not worth guessing at.
            log.warn("No user {} to notify about an order update", userId);
            return;
        }
        if (!user.isNotifyOrderUpdates()) {
            return;
        }

        // Still routed through PushNotificationService rather than inlined, so its
        // subscription handling — including dropping subscriptions the browser has
        // discarded — stays in one place. It re-checks the preference, harmlessly.
        pushNotificationService.notifyUser(userId, subject, body);

        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            emailService.sendOrderUpdateEmail(user.getEmail(), user.getName(), subject, body);
        }
    }
}
