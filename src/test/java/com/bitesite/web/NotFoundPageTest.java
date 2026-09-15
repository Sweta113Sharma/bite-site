package com.bitesite.web;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * A URL nothing is mapped to reaches GlobalExceptionHandler as NoResourceFoundException.
 *
 * <p>For a while that handler called {@code response.sendError(404)}, which hands the
 * request to the container's /error page. In production that rendered Spring's
 * "Whitelabel Error Page" for any mistyped or stale link, and /api callers got HTML
 * instead of an ApiError. MockMvc does not forward to /error, so under the old handler
 * both tests below see an empty body and fail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotFoundPageTest {

    private static final String APP_HOST = "app.localhost";

    @Autowired private MockMvc mockMvc;

    private static AppUserPrincipal student() {
        return new AppUserPrincipal(User.builder().id(900_001L).tenantId(1L).name("Lost Student")
                .email("lost-student@test.local").role(Role.USER).activeRole(Role.USER)
                .active(true).emailVerified(true).phoneVerified(true).build());
    }

    @Test
    void aSignedInStudentOnAMistypedLinkGetsTheBrandedPage() throws Exception {
        mockMvc.perform(get("/student/this-page-does-not-exist").header("Host", APP_HOST)
                        .with(user(student())))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/generic"))
                .andExpect(content().string(containsString("Error 404")))
                .andExpect(content().string(not(containsString("Whitelabel"))));
    }

    @Test
    void anUnknownApiPathAnswersWithAnApiError() throws Exception {
        mockMvc.perform(get("/api/this-does-not-exist").header("Host", APP_HOST)
                        .accept(MediaType.APPLICATION_JSON)
                        .with(user(student())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Not found."));
    }
}
