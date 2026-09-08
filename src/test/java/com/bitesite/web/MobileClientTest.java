package com.bitesite.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decides who sees the welcome screen at {@code /}, so the cases worth pinning are the
 * two real clients (the Capacitor Android apps, and a desktop browser) plus the ways the
 * signal can be absent or contradictory.
 */
class MobileClientTest {

    private static MockHttpServletRequest request(String hint, String userAgent) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        if (hint != null) {
            r.addHeader("Sec-CH-UA-Mobile", hint);
        }
        if (userAgent != null) {
            r.addHeader("User-Agent", userAgent);
        }
        return r;
    }

    private static final String ANDROID_WEBVIEW =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UQ1A) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String DESKTOP_CHROME =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Safari/537.36";
    private static final String IPHONE_SAFARI =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Version/17.0 Mobile/15E148 Safari/604.1";
    private static final String IPAD_SAFARI =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Version/17.0 Safari/605.1.15";

    @Test
    void clientHintDecidesWhenPresent() {
        assertThat(MobileClient.isMobile(request("?1", null))).isTrue();
        assertThat(MobileClient.isMobile(request("?0", null))).isFalse();
    }

    @Test
    void clientHintBeatsAContradictoryUserAgent() {
        // The hint is the browser answering the question; the UA string is a guess at it.
        assertThat(MobileClient.isMobile(request("?0", ANDROID_WEBVIEW))).isFalse();
        assertThat(MobileClient.isMobile(request("?1", DESKTOP_CHROME))).isTrue();
    }

    @Test
    void fallsBackToUserAgentWhenNoHint() {
        assertThat(MobileClient.isMobile(request(null, ANDROID_WEBVIEW))).as("android app").isTrue();
        assertThat(MobileClient.isMobile(request(null, IPHONE_SAFARI))).as("iphone").isTrue();
        assertThat(MobileClient.isMobile(request(null, DESKTOP_CHROME))).as("desktop").isFalse();
    }

    @Test
    void iPadIsTreatedAsDesktop() {
        // iPadOS reports itself as a Mac and cannot be told apart from one by string alone.
        // A tablet-width window is the right place for the sign-in form regardless.
        assertThat(MobileClient.isMobile(request(null, IPAD_SAFARI))).isFalse();
    }

    @Test
    void noUserAgentIsNotAPhone() {
        // Health checks and scrapers send none. They should not get the marketing screen.
        assertThat(MobileClient.isMobile(request(null, null))).isFalse();
    }
}
