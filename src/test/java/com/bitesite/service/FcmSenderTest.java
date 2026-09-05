package com.bitesite.service;

import com.bitesite.dao.FcmTokenDao;
import com.bitesite.dao.UserDao;
import com.bitesite.model.FcmToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Covers the parts of {@link FcmSender} that hold without a Firebase project: the
 * unconfigured short-circuit and token bookkeeping.
 *
 * <p>The send path itself is not exercised here. It needs a real initialised
 * {@code FirebaseApp} and therefore real service-account credentials, which do not belong
 * in a unit test. What that leaves untested is the delivery call; the decision of *whether*
 * to send is pinned both here (unconfigured) and in {@link OrderNotifierTest} (opted out).
 */
@ExtendWith(MockitoExtension.class)
class FcmSenderTest {

    @Mock private FcmTokenDao fcmTokenDao;
    @Mock private UserDao userDao;

    private FcmSender unconfigured() {
        // Neither credential source set is the state every developer and every CI run is
        // in; init() leaves firebaseApp null and isConfigured() false.
        FcmSender sender = new FcmSender(fcmTokenDao, userDao, "", "");
        sender.init();
        return sender;
    }

    @Test
    void isNotConfiguredWithoutCredentials() {
        assertThat(unconfigured().isConfigured()).isFalse();
    }

    @Test
    void badCredentialsDisableTheChannelRatherThanFailStartup() {
        // A malformed key should cost the notification channel, not the ability to take
        // orders — init() must swallow it and leave the sender simply unconfigured.
        FcmSender sender = new FcmSender(fcmTokenDao, userDao, "{not-valid-json", "");
        sender.init();

        assertThat(sender.isConfigured()).isFalse();
    }

    @Test
    void notifyUserDoesNothingWhenNotConfigured() {
        unconfigured().notifyUser(1L, "Order ready for pickup", "Show code 1234.");

        // Short-circuits before it reads either table: no tokens fetched, no preference
        // looked up.
        verifyNoInteractions(fcmTokenDao);
        verifyNoInteractions(userDao);
    }

    @Test
    void registeringATokenStoresItAgainstTheUser() {
        unconfigured().registerToken(7L, "device-token", "android");

        ArgumentCaptor<FcmToken> saved = ArgumentCaptor.forClass(FcmToken.class);
        verify(fcmTokenDao).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(7L);
        assertThat(saved.getValue().getToken()).isEqualTo("device-token");
    }

    @Test
    void aMissingPlatformDefaultsToAndroid() {
        unconfigured().registerToken(7L, "device-token", null);

        ArgumentCaptor<FcmToken> saved = ArgumentCaptor.forClass(FcmToken.class);
        verify(fcmTokenDao).save(saved.capture());
        assertThat(saved.getValue().getPlatform()).isEqualTo("android");
    }

    /** Registration must not depend on this server currently being able to send, or every
     * app install would have to be reopened after credentials were finally configured. */
    @Test
    void tokensAreStoredEvenWhileTheChannelIsOff() {
        FcmSender sender = unconfigured();
        assertThat(sender.isConfigured()).isFalse();

        sender.registerToken(7L, "device-token", "android");

        verify(fcmTokenDao).save(any(FcmToken.class));
    }

    /** Possession of a token string is not proof of owning the device, so the delete is
     * scoped to the caller and reports honestly when it matched nothing. */
    @Test
    void unregisteringSomeoneElsesTokenRemovesNothing() {
        when(fcmTokenDao.deleteByTokenForUser("someone-elses", 7L)).thenReturn(0);

        assertThat(unconfigured().unregisterToken(7L, "someone-elses")).isFalse();
    }

    @Test
    void unregisteringYourOwnTokenReportsTheRemoval() {
        when(fcmTokenDao.deleteByTokenForUser("mine", 7L)).thenReturn(1);

        assertThat(unconfigured().unregisterToken(7L, "mine")).isTrue();
    }
}
