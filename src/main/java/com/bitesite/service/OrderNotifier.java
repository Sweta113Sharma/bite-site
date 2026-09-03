package com.bitesite.service;

import com.bitesite.config.RateLimiter;
import com.bitesite.dao.PushSubscriptionDao;
import com.bitesite.dao.UserDao;
import com.bitesite.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Tells a student what happened to their order, over the cheapest channel that reaches
 * them.
 *
 * <p>Push was the only channel and its keys had never been generated, so in production
 * nobody was told anything. Email closes that — but the mail plan allows 300 messages a
 * day in total, and those same 300 carry every verification code, password reset, address
 * confirmation and admin sign-in code in the product. Order updates are the highest-volume
 * thing here by an order of magnitude: unbudgeted, two emails per order would exhaust the
 * day's allowance somewhere around the hundred-and-fiftieth lunch and the next person to
 * forget their password would silently get nothing.
 *
 * <p>So email is a fallback, not a duplicate, and it is capped:
 *
 * <ul>
 *   <li><b>Push first, always.</b> It is free and unlimited. A student with a live
 *       subscription is never emailed about an order — they have already been told.</li>
 *   <li><b>Email only for those push cannot reach</b>, and only for updates worth a
 *       message. Payment confirmation is push-only: it is the most frequent event and the
 *       app shows it immediately anyway. Ready, cancelled and refunded are worth it.</li>
 *   <li><b>A daily ceiling</b> on order email, well below the plan's limit, so a busy
 *       lunchtime cannot consume the allowance that verification and password resets
 *       depend on. Hitting it degrades order notifications, never sign-in.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderNotifier {

    /**
     * Order emails allowed per day, against a plan of 300.
     *
     * <p>The gap is the point: whatever happens to lunch, at least 150 messages remain for
     * the codes people cannot sign in without. Order email is the load that varies, so it
     * is the load that gets the ceiling.
     */
    private static final int DAILY_ORDER_EMAIL_BUDGET = 150;

    private static final Duration ONE_DAY = Duration.ofDays(1);
    private static final String BUDGET_KEY = "order-email-daily";

    private final UserDao userDao;
    private final PushSubscriptionDao pushSubscriptionDao;
    private final PushNotificationService pushNotificationService;
    private final EmailService emailService;
    private final RateLimiter rateLimiter;

    /** Whether an update is worth spending a message from a finite daily allowance on. */
    public enum Delivery {
        /** Frequent, and already visible in the app the moment it happens. */
        PUSH_ONLY,
        /** Time-critical or about money — worth reaching someone who has no push. */
        PUSH_AND_EMAIL
    }

    public void notifyOrderUpdate(Long userId, String subject, String body, Delivery delivery) {
        User user = userDao.findById(userId).orElse(null);
        if (user == null) {
            log.warn("No user {} to notify about an order update", userId);
            return;
        }
        // One opt-out governs every channel. It already existed and already gated push;
        // honouring it here is what makes turning it off actually stop everything.
        if (!user.isNotifyOrderUpdates()) {
            return;
        }

        pushNotificationService.notifyUser(userId, subject, body);

        if (delivery != Delivery.PUSH_AND_EMAIL) {
            return;
        }
        if (!hasUsableAddress(user)) {
            return;
        }
        // Someone push can reach has already been told; a second copy by email would spend
        // a message from a budget that other people's sign-ins depend on.
        if (pushNotificationService.isConfigured() && !pushSubscriptionDao.findByUserId(userId).isEmpty()) {
            return;
        }
        if (!rateLimiter.tryConsume(BUDGET_KEY, DAILY_ORDER_EMAIL_BUDGET, ONE_DAY)) {
            // Deliberately quiet for the student and loud in the log: the app still shows
            // the order, and the alternative is spending the allowance sign-in needs.
            log.warn("Daily order-email budget of {} reached — order update to user {} not emailed",
                    DAILY_ORDER_EMAIL_BUDGET, userId);
            return;
        }
        emailService.sendOrderUpdateEmail(user.getEmail(), user.getName(), subject, body);
    }

    private boolean hasUsableAddress(User user) {
        // deleteOwnAccount leaves the row in place so order history keeps pointing
        // somewhere; nothing should be sent to what is left of it.
        return user.getEmail() != null && !user.getEmail().isBlank();
    }
}
