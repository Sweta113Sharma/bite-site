package com.bitesite.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The welcome screen is a phone-shaped app screen, so {@code /} serves it only to phones
 * and sends everyone else to the sign-in form. Asserted at the route rather than only on
 * {@link MobileClient}, because the detection being right is no use if the branch is not
 * wired to it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WelcomeRoutingTest {

    private static final String APP_HOST = "app.localhost";
    private static final String OUTLET_HOST = "outlet.localhost";
    private static final String ADMIN_HOST = "admin.localhost";
    private static final String ANDROID =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String DESKTOP =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Safari/537.36";

    @Autowired private MockMvc mockMvc;

    @Test
    void phoneGetsTheWelcomeScreen() throws Exception {
        mockMvc.perform(get("/").header("Host", APP_HOST).header("User-Agent", ANDROID))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Skip the queue")));
    }

    @Test
    void desktopIsSentToSignIn() throws Exception {
        mockMvc.perform(get("/").header("Host", APP_HOST).header("User-Agent", DESKTOP))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void staffPortalsNeverGetTheCustomerWelcome() throws Exception {
        // Even on a phone. Its second button is "Create account", which on these hosts
        // would invite a canteen manager to register as a student.
        mockMvc.perform(get("/").header("Host", OUTLET_HOST).header("User-Agent", ANDROID))
                .andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/").header("Host", ADMIN_HOST).header("User-Agent", ANDROID))
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void clientHintOverridesTheUserAgent() throws Exception {
        mockMvc.perform(get("/").header("Host", APP_HOST).header("User-Agent", DESKTOP)
                        .header("Sec-CH-UA-Mobile", "?1"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/").header("Host", APP_HOST).header("User-Agent", ANDROID)
                        .header("Sec-CH-UA-Mobile", "?0"))
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void accountDeletionInstructionsArePublic() throws Exception {
        mockMvc.perform(get("/account-deletion").header("Host", APP_HOST))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Delete your BiteSite account")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("My account")));
    }
}
