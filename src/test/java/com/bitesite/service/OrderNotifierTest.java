package com.bitesite.service;

import com.bitesite.dao.UserDao;
import com.bitesite.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Order updates go out over push and nothing else. Email is not a fallback here: the mail
 * plan is 300 messages a day shared with every sign-in code in the product, and order
 * volume would eat it.
 *
 * <p>The preference check is the part worth pinning. It is the single opt-out for order
 * notifications, and it has to keep meaning "all channels" when the native app becomes a
 * second one.
 */
@ExtendWith(MockitoExtension.class)
class OrderNotifierTest {

    @Mock private UserDao userDao;
    @Mock private PushNotificationService pushNotificationService;

    private OrderNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new OrderNotifier(userDao, pushNotificationService);
    }

    private User student(boolean notify) {
        return User.builder().id(7L).name("A Student").email("student@demo.local")
                .notifyOrderUpdates(notify).build();
    }

    @Test
    void anUpdateIsPushed() {
        when(userDao.findById(7L)).thenReturn(Optional.of(student(true)));

        notifier.notifyOrderUpdate(7L, "Order ready for pickup", "Show code 1234.");

        verify(pushNotificationService).notifyUser(7L, "Order ready for pickup", "Show code 1234.");
    }

    @Test
    void turningOrderNotificationsOffStopsTheUpdate() {
        when(userDao.findById(7L)).thenReturn(Optional.of(student(false)));

        notifier.notifyOrderUpdate(7L, "Order ready for pickup", "Show code 1234.");

        verifyNoInteractions(pushNotificationService);
    }

    @Test
    void aMissingUserIsNotGuessedAt() {
        when(userDao.findById(7L)).thenReturn(Optional.empty());

        notifier.notifyOrderUpdate(7L, "Order ready", "body");

        verifyNoInteractions(pushNotificationService);
    }
}
