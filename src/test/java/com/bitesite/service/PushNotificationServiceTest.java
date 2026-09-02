package com.bitesite.service;

import com.bitesite.dao.PushSubscriptionDao;
import com.bitesite.dao.UserDao;
import com.bitesite.model.PushSubscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PushNotificationServiceTest {

    @Mock private PushSubscriptionDao pushSubscriptionDao;
    @Mock private UserDao userDao;

    @Test
    void isNotConfiguredWhenKeysAreBlank() {
        PushNotificationService service = new PushNotificationService(pushSubscriptionDao, userDao, "", "", "mailto:a@b.com");

        assertThat(service.isConfigured()).isFalse();
    }

    @Test
    void isConfiguredWhenBothKeysArePresent() {
        PushNotificationService service = new PushNotificationService(
                pushSubscriptionDao, userDao, "pub", "priv", "mailto:a@b.com");

        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    void notifyUserDoesNothingWhenNotConfigured() {
        PushNotificationService service = new PushNotificationService(pushSubscriptionDao, userDao, "", "", "mailto:a@b.com");

        service.notifyUser(1L, "Title", "Body");

        verifyNoInteractions(pushSubscriptionDao);
    }

    @Test
    void subscribeSavesTheSubscription() {
        PushNotificationService service = new PushNotificationService(
                pushSubscriptionDao, userDao, "pub", "priv", "mailto:a@b.com");

        service.subscribe(7L, "https://push.example/abc", "p256dh-key", "auth-key");

        verify(pushSubscriptionDao).save(argThat((PushSubscription s) ->
                s.getUserId().equals(7L) && s.getEndpoint().equals("https://push.example/abc")
                        && s.getP256dhKey().equals("p256dh-key") && s.getAuthKey().equals("auth-key")));
    }

    @Test
    void unsubscribeDeletesTheCallersOwnEndpoint() {
        PushNotificationService service = new PushNotificationService(
                pushSubscriptionDao, userDao, "pub", "priv", "mailto:a@b.com");

        service.unsubscribe(7L, "https://push.example/abc");

        // Scoped to the user: an endpoint alone is not proof of ownership.
        verify(pushSubscriptionDao).deleteByEndpointForUser("https://push.example/abc", 7L);
    }

    // Sending a real push (PushService.send) requires a live, well-formed VAPID key pair
    // and a real (or at least well-formed) push endpoint — "pub"/"priv" above are not
    // valid EC keys, so notifyUser() with a subscription present would throw inside the
    // try/catch and log, never propagate. That's exactly the "never breaks the caller"
    // contract this method promises; a real send round-trip needs a live push endpoint
    // and isn't something this test suite can exercise (same boundary as Twilio/SMS).
    @Test
    void notifyUserNeverThrowsEvenWhenTheGatewayCallFails() {
        PushNotificationService service = new PushNotificationService(
                pushSubscriptionDao, userDao, "pub", "priv", "mailto:a@b.com");
        // Opted in, or notifyUser returns before it ever reaches a subscription.
        when(userDao.findById(1L)).thenReturn(java.util.Optional.of(
                com.bitesite.model.User.builder().id(1L).notifyOrderUpdates(true).build()));
        when(pushSubscriptionDao.findByUserId(1L)).thenReturn(List.of(PushSubscription.builder()
                .id(1L).userId(1L).endpoint("https://push.example/abc")
                .p256dhKey("not-a-real-key").authKey("not-a-real-key").build()));

        service.notifyUser(1L, "Title", "Body");

        // No exception propagated — that's the assertion. Also confirm it didn't
        // mistake a crypto failure for a dead subscription and delete it.
        verify(pushSubscriptionDao, never()).deleteByEndpoint(any());
    }

    @Test
    void notifyUserSendsNothingToSomeoneWhoTurnedOrderUpdatesOff() {
        PushNotificationService service = new PushNotificationService(
                pushSubscriptionDao, userDao, "pub", "priv", "mailto:a@b.com");
        when(userDao.findById(1L)).thenReturn(java.util.Optional.of(
                com.bitesite.model.User.builder().id(1L).notifyOrderUpdates(false).build()));

        service.notifyUser(1L, "Title", "Body");

        // Checked once here rather than at each call site, so a future caller cannot
        // forget it and quietly message someone who opted out.
        verify(pushSubscriptionDao, never()).findByUserId(anyLong());
    }

    @Test
    void anUnknownUserIsTreatedAsOptedOut() {
        PushNotificationService service = new PushNotificationService(
                pushSubscriptionDao, userDao, "pub", "priv", "mailto:a@b.com");
        when(userDao.findById(99L)).thenReturn(java.util.Optional.empty());

        service.notifyUser(99L, "Title", "Body");

        // Fails closed: silence is the safe default for a message.
        verify(pushSubscriptionDao, never()).findByUserId(anyLong());
    }
}
