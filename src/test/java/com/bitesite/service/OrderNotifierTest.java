package com.bitesite.service;

import com.bitesite.config.RateLimiter;
import com.bitesite.dao.PushSubscriptionDao;
import com.bitesite.dao.UserDao;
import com.bitesite.model.PushSubscription;
import com.bitesite.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * One opt-out has to govern every channel. Before this fan-out existed the preference
 * gated push and nothing else, so adding email is exactly the change that could turn
 * "notifications off" into "notifications off, except the new ones".
 */
@ExtendWith(MockitoExtension.class)
class OrderNotifierTest {

    @Mock private UserDao userDao;
    @Mock private PushSubscriptionDao pushSubscriptionDao;
    @Mock private PushNotificationService pushNotificationService;
    @Mock private EmailService emailService;
    @Mock private RateLimiter rateLimiter;

    private OrderNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new OrderNotifier(userDao, pushSubscriptionDao, pushNotificationService,
                emailService, rateLimiter);
    }

    private User student(boolean notify) {
        return User.builder().id(7L).name("A Student").email("student@demo.local")
                .notifyOrderUpdates(notify).build();
    }

    @Test
    void someoneWithNoPushIsEmailed() {
        when(userDao.findById(7L)).thenReturn(Optional.of(student(true)));
        when(pushNotificationService.isConfigured()).thenReturn(true);
        when(pushSubscriptionDao.findByUserId(7L)).thenReturn(List.of());
        when(rateLimiter.tryConsume(anyString(), anyInt(), any())).thenReturn(true);

        notifier.notifyOrderUpdate(7L, "Order ready for pickup", "Show code 1234.",
                OrderNotifier.Delivery.PUSH_AND_EMAIL);

        verify(pushNotificationService).notifyUser(7L, "Order ready for pickup", "Show code 1234.");
        verify(emailService).sendOrderUpdateEmail("student@demo.local", "A Student",
                "Order ready for pickup", "Show code 1234.");
    }

    /**
     * The mail plan is 300 messages a day and those same messages carry every sign-in
     * code. Someone push already reached does not get a second copy paid for out of that.
     */
    @Test
    void someonePushAlreadyReachedIsNotAlsoEmailed() {
        when(userDao.findById(7L)).thenReturn(Optional.of(student(true)));
        when(pushNotificationService.isConfigured()).thenReturn(true);
        when(pushSubscriptionDao.findByUserId(7L)).thenReturn(List.of(
                PushSubscription.builder().userId(7L).endpoint("https://push.example/abc").build()));

        notifier.notifyOrderUpdate(7L, "Order ready for pickup", "Show code 1234.",
                OrderNotifier.Delivery.PUSH_AND_EMAIL);

        verify(pushNotificationService).notifyUser(7L, "Order ready for pickup", "Show code 1234.");
        verifyNoInteractions(emailService);
        // No budget was spent either.
        verifyNoInteractions(rateLimiter);
    }

    /** Payment confirmation is the highest-volume event and is visible in the app at once,
     * so it never spends a message. */
    @Test
    void aPushOnlyUpdateIsNeverEmailed() {
        when(userDao.findById(7L)).thenReturn(Optional.of(student(true)));

        notifier.notifyOrderUpdate(7L, "Order confirmed", "We have your payment.",
                OrderNotifier.Delivery.PUSH_ONLY);

        verify(pushNotificationService).notifyUser(7L, "Order confirmed", "We have your payment.");
        verifyNoInteractions(emailService);
        verifyNoInteractions(rateLimiter);
    }

    /** Once the day's order-email ceiling is reached, order notifications degrade — the
     * allowance that sign-in codes depend on is what is being protected. */
    @Test
    void orderEmailStopsAtTheDailyCeilingRatherThanEatingTheSignInAllowance() {
        when(userDao.findById(7L)).thenReturn(Optional.of(student(true)));
        when(pushNotificationService.isConfigured()).thenReturn(true);
        when(pushSubscriptionDao.findByUserId(7L)).thenReturn(List.of());
        when(rateLimiter.tryConsume(anyString(), anyInt(), any())).thenReturn(false);

        notifier.notifyOrderUpdate(7L, "Order ready for pickup", "Show code 1234.",
                OrderNotifier.Delivery.PUSH_AND_EMAIL);

        // Push still went out — it is free.
        verify(pushNotificationService).notifyUser(7L, "Order ready for pickup", "Show code 1234.");
        verify(emailService, never()).sendOrderUpdateEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void turningOrderNotificationsOffStopsEmailAsWellAsPush() {
        when(userDao.findById(7L)).thenReturn(Optional.of(student(false)));

        notifier.notifyOrderUpdate(7L, "Order ready for pickup", "Show code 1234.",
                OrderNotifier.Delivery.PUSH_AND_EMAIL);

        verifyNoInteractions(pushNotificationService);
        verifyNoInteractions(emailService);
    }

    @Test
    void anAnonymisedAccountWithNoUsableAddressIsNotEmailed() {
        // deleteOwnAccount leaves the row in place so order history keeps pointing
        // somewhere. Nothing should then be sent to whatever is left of it.
        when(userDao.findById(7L)).thenReturn(Optional.of(
                User.builder().id(7L).name("Deleted User").email("  ").notifyOrderUpdates(true).build()));

        // The blank-address check comes before any channel lookup, so nothing else is
        // consulted.
        notifier.notifyOrderUpdate(7L, "Order cancelled", "Sorry.",
                OrderNotifier.Delivery.PUSH_AND_EMAIL);

        verify(emailService, never()).sendOrderUpdateEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aMissingUserIsNotGuessedAt() {
        when(userDao.findById(7L)).thenReturn(Optional.empty());

        notifier.notifyOrderUpdate(7L, "Order ready", "body", OrderNotifier.Delivery.PUSH_AND_EMAIL);

        verifyNoInteractions(pushNotificationService);
        verifyNoInteractions(emailService);
    }
}
