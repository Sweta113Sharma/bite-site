package com.bitesite.service;

import com.bitesite.dao.UserDao;
import com.bitesite.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
    @Mock private PushNotificationService pushNotificationService;
    @Mock private EmailService emailService;

    private OrderNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new OrderNotifier(userDao, pushNotificationService, emailService);
    }

    private User student(boolean notify) {
        return User.builder().id(7L).name("A Student").email("student@demo.local")
                .notifyOrderUpdates(notify).build();
    }

    @Test
    void anUpdateGoesOutOverBothChannels() {
        when(userDao.findById(7L)).thenReturn(Optional.of(student(true)));

        notifier.notifyOrderUpdate(7L, "Order ready for pickup", "Show code 1234.");

        verify(pushNotificationService).notifyUser(7L, "Order ready for pickup", "Show code 1234.");
        verify(emailService).sendOrderUpdateEmail("student@demo.local", "A Student",
                "Order ready for pickup", "Show code 1234.");
    }

    @Test
    void turningOrderNotificationsOffStopsEmailAsWellAsPush() {
        when(userDao.findById(7L)).thenReturn(Optional.of(student(false)));

        notifier.notifyOrderUpdate(7L, "Order ready for pickup", "Show code 1234.");

        verifyNoInteractions(pushNotificationService);
        verifyNoInteractions(emailService);
    }

    @Test
    void anAnonymisedAccountWithNoUsableAddressIsNotEmailed() {
        // deleteOwnAccount leaves the row in place so order history keeps pointing
        // somewhere. Nothing should then be sent to whatever is left of it.
        when(userDao.findById(7L)).thenReturn(Optional.of(
                User.builder().id(7L).name("Deleted User").email("  ").notifyOrderUpdates(true).build()));

        notifier.notifyOrderUpdate(7L, "Order cancelled", "Sorry.");

        verify(emailService, never()).sendOrderUpdateEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aMissingUserIsNotGuessedAt() {
        when(userDao.findById(7L)).thenReturn(Optional.empty());

        notifier.notifyOrderUpdate(7L, "Order ready", "body");

        verifyNoInteractions(pushNotificationService);
        verifyNoInteractions(emailService);
    }
}
