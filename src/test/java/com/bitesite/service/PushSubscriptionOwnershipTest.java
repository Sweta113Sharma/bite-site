package com.bitesite.service;

import com.bitesite.dao.PushSubscriptionDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * A push endpoint is not a secret — the browser hands it to whatever server the page posts
 * to — so it cannot be treated as proof of ownership on its own.
 */
@ExtendWith(MockitoExtension.class)
class PushSubscriptionOwnershipTest {

    @Mock private PushSubscriptionDao pushSubscriptionDao;

    /** VAPID keys blank: these tests are about subscription bookkeeping, not sending. */
    private PushNotificationService service() {
        return new PushNotificationService(pushSubscriptionDao, "", "", "mailto:x@y.z");
    }

    @Test
    void unsubscribeOnlyTouchesTheCallersOwnEndpoint() {
        when(pushSubscriptionDao.deleteByEndpointForUser("https://push/abc", 7L)).thenReturn(1);

        assertThat(service().unsubscribe(7L, "https://push/abc")).isTrue();

        verify(pushSubscriptionDao).deleteByEndpointForUser("https://push/abc", 7L);
        // The unscoped delete must never be reachable from a request path.
        verify(pushSubscriptionDao, never()).deleteByEndpoint(anyString());
    }

    @Test
    void unsubscribingSomeoneElsesEndpointRemovesNothing() {
        // Previously this deleted by endpoint alone, so knowing another person's endpoint
        // string was enough to silence their notifications.
        when(pushSubscriptionDao.deleteByEndpointForUser("https://push/victim", 7L)).thenReturn(0);

        assertThat(service().unsubscribe(7L, "https://push/victim")).isFalse();
    }

    @Test
    void unsubscribeAllDropsEveryDeviceForOneUser() {
        service().unsubscribeAll(7L);

        verify(pushSubscriptionDao).deleteByUserId(7L);
    }
}
