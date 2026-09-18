package com.bitesite.security;

import com.bitesite.config.RateLimiter;
import com.bitesite.dao.UserDao;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import com.bitesite.tenant.Tenant;
import com.bitesite.tenant.TenantDao;
import com.bitesite.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The login limit counts wrong passwords, not sign-ins. It used to count every POST, so the
 * eleventh student on one campus network inside five minutes was refused with the right
 * password. Each test uses its own fake address, because the counters live in a real table.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginRateLimitTest {

    private static final String PASSWORD = "Right@12345";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantDao tenantDao;
    @Autowired private UserDao userDao;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RateLimiter rateLimiter;

    private final String runId = UUID.randomUUID().toString().substring(0, 8);
    private Long tenantId;
    private String hash;

    @BeforeAll
    void setUp() {
        tenantId = tenantDao.save(Tenant.builder().name("Login Limit College " + runId)
                .status(TenantStatus.ACTIVE).build()).getId();
        hash = passwordEncoder.encode(PASSWORD);
    }

    private User student(String name) {
        return userDao.save(User.builder().tenantId(tenantId).name(name)
                .email(name + "-" + runId + "@test.local").passwordHash(hash)
                .role(Role.USER).activeRole(Role.USER).active(true)
                .emailVerified(true).phoneVerified(true).build());
    }

    private static String freshAddress() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(1, 255) + "." + r.nextInt(1, 255) + "." + r.nextInt(1, 255);
    }

    /** Where the login redirected to. */
    private String login(String address, String email, String password) throws Exception {
        return mockMvc.perform(post("/login").with(csrf()).remoteAddress(address)
                        .param("username", email).param("password", password))
                .andReturn().getResponse().getRedirectedUrl();
    }

    @Test
    void aWholeCampusBehindOneAddressCanSignIn() throws Exception {
        String campus = freshAddress();
        for (int i = 0; i < 25; i++) {
            User s = student("campus" + i);
            assertThat(login(campus, s.getEmail(), PASSWORD))
                    .as("student %d on a shared address", i + 1).doesNotContain("error");
        }
    }

    @Test
    void guessingOnePasswordLocksThatAccountFromThatAddressOnly() throws Exception {
        String attacker = freshAddress();
        User victim = student("victim");
        User neighbour = student("neighbour");

        for (int i = 0; i < 10; i++) {
            assertThat(login(attacker, victim.getEmail(), "wrong-" + i)).isEqualTo("/login?error");
        }
        assertThat(login(attacker, victim.getEmail(), PASSWORD))
                .as("the 11th try on the same account from the same address").isEqualTo("/login?error=ratelimit");
        // Same letters, different case and spacing: still the same account.
        assertThat(login(attacker, "  " + victim.getEmail().toUpperCase() + " ", PASSWORD))
                .isEqualTo("/login?error=ratelimit");

        assertThat(login(attacker, neighbour.getEmail(), PASSWORD))
                .as("someone else on that network").doesNotContain("error");
        assertThat(login(freshAddress(), victim.getEmail(), PASSWORD))
                .as("the victim from their own network").doesNotContain("error");
    }

    @Test
    void sprayingAcrossManyAccountsFromOneAddressIsStopped() throws Exception {
        String sprayer = freshAddress();
        User student = student("sprayed");
        // 100 wrong passwords against 100 different accounts, counted directly rather than
        // through 100 BCrypt checks; the single real failure below goes through the handler.
        for (int i = 0; i < 99; i++) {
            rateLimiter.tryConsume("login-fail:" + sprayer, 100, Duration.ofMinutes(5));
        }
        assertThat(login(sprayer, "nobody-" + runId + "@test.local", "guess")).isEqualTo("/login?error");
        assertThat(login(sprayer, student.getEmail(), PASSWORD))
                .as("after 100 failures from one address").isEqualTo("/login?error=ratelimit");
    }
}
