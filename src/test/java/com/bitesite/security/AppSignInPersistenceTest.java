package com.bitesite.security;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.dao.OutletDao;
import com.bitesite.dao.UserDao;
import com.bitesite.model.Outlet;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import com.bitesite.tenant.Tenant;
import com.bitesite.tenant.TenantDao;
import com.bitesite.tenant.TenantStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Android apps signed people out every time they were opened.
 *
 * <p>Capacitor clears every cookie without an expiry when the app starts, and the SESSION
 * cookie had none. A sign-in from the app now asks to be kept, which gives the cookie a
 * Max-Age and the session row a 30-day idle timeout (AppRememberMeServices). These tests
 * pin both halves over real HTTP and a real SPRING_SESSION table, plus the two things that
 * must not change: browser and admin-portal sign-ins keep the 30 minutes, and every way
 * of ending a session still ends a kept one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppSignInPersistenceTest {

    private static final String APP_HOST = "app.localhost";
    private static final String OUTLET_HOST = "outlet.localhost";
    private static final String ADMIN_HOST = "admin.localhost";
    private static final String PASSWORD = "Password1!";

    private static final int THIRTY_DAYS = 30 * 24 * 60 * 60;
    private static final int THIRTY_MINUTES = 30 * 60;

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantDao tenantDao;
    @Autowired private OutletDao outletDao;
    @Autowired private UserDao userDao;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Tenant tenant;
    private Outlet outlet;

    @BeforeAll
    void seedTenant() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantDao.save(Tenant.builder().name("Kept Sign-in College " + runId)
                .status(TenantStatus.ACTIVE).build());
        outlet = outletDao.save(Outlet.builder().tenantId(tenant.getId())
                .name("Kept Sign-in Canteen " + runId).active(true).build());
    }

    /** One device: its own IP (the login throttle is IP-keyed) and its own cookie jar. */
    private final class Device {

        private final String remoteAddr = "10.%d.%d.%d".formatted(
                ThreadLocalRandom.current().nextInt(1, 255),
                ThreadLocalRandom.current().nextInt(1, 255),
                ThreadLocalRandom.current().nextInt(1, 255));
        private final String host;
        private Cookie[] cookies = new Cookie[0];

        Device(String host) {
            this.host = host;
        }

        MvcResult perform(MockHttpServletRequestBuilder builder) throws Exception {
            builder.header("Host", host).remoteAddress(remoteAddr);
            if (cookies.length > 0) {
                builder.cookie(cookies);
            }
            MvcResult result = mockMvc.perform(builder).andReturn();
            Cookie[] returned = result.getResponse().getCookies();
            if (returned != null && returned.length > 0) {
                cookies = returned;
            }
            return result;
        }

        /** Signs in the way the login page does, inside the app or out of it. */
        Cookie signIn(User user, boolean fromApp, String expectedLanding) throws Exception {
            MockHttpServletRequestBuilder login = post("/login").with(csrf())
                    .param("username", user.getEmail()).param("password", PASSWORD);
            if (fromApp) {
                login.param("remember-me", "true");
            }
            MvcResult result = perform(login);
            assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(expectedLanding);
            return sessionCookie(result);
        }

        /** 200 while the session holds, a redirect to sign-in once it does not. */
        int probe() throws Exception {
            return perform(get("/account/password")).getResponse().getStatus();
        }
    }

    private static Cookie sessionCookie(MvcResult result) {
        Cookie[] matching = Arrays.stream(result.getResponse().getCookies())
                .filter(c -> c.getName().equals("SESSION"))
                .toArray(Cookie[]::new);
        assertThat(matching).as("the login response sets the session cookie").isNotEmpty();
        return matching[matching.length - 1];
    }

    private int idleTimeoutOf(Cookie session) {
        String sessionId = new String(Base64.getDecoder().decode(session.getValue()), StandardCharsets.UTF_8);
        return jdbcTemplate.queryForObject(
                "SELECT MAX_INACTIVE_INTERVAL FROM SPRING_SESSION WHERE SESSION_ID = ?", Integer.class, sessionId);
    }

    private User seedUser(String label, Role role) {
        boolean platform = role == Role.SUPER_ADMIN || role == Role.TECH_MANAGER;
        boolean outletRole = role == Role.CANTEEN_MANAGER || role == Role.CANTEEN_OPERATOR;
        return userDao.save(User.builder()
                .tenantId(platform ? null : tenant.getId())
                .outletId(outletRole ? outlet.getId() : null)
                .name(label)
                .email(label + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .role(role).activeRole(role).active(true)
                .emailVerified(true).phoneVerified(true)
                .build());
    }

    @Test
    void anAppSignInGetsACookieThatSurvivesTheAppClosing() throws Exception {
        User student = seedUser("kept-student", Role.USER);
        Device phone = new Device(APP_HOST);

        Cookie session = phone.signIn(student, true, "/student/menu");

        // A cookie with no Max-Age is a session cookie, which is what Capacitor deletes.
        assertThat(session.getMaxAge()).isGreaterThan(THIRTY_DAYS);
        assertThat(idleTimeoutOf(session)).isEqualTo(THIRTY_DAYS);
        assertThat(phone.probe()).isEqualTo(200);
    }

    @Test
    void theOutletAppKeepsItsSignInToo() throws Exception {
        User operator = seedUser("kept-operator", Role.CANTEEN_OPERATOR);

        Cookie session = new Device(OUTLET_HOST).signIn(operator, true, "/canteen/queue");

        assertThat(session.getMaxAge()).isGreaterThan(THIRTY_DAYS);
        assertThat(idleTimeoutOf(session)).isEqualTo(THIRTY_DAYS);
    }

    @Test
    void aBrowserSignInKeepsTheThirtyMinuteSession() throws Exception {
        User student = seedUser("browser-student", Role.USER);

        Cookie session = new Device(APP_HOST).signIn(student, false, "/student/menu");

        assertThat(session.getMaxAge()).as("still a session cookie").isLessThan(0);
        assertThat(idleTimeoutOf(session)).isEqualTo(THIRTY_MINUTES);
    }

    @Test
    void theAdminPortalRefusesToKeepASignInEvenWhenAsked() throws Exception {
        User admin = seedUser("kept-admin", Role.SUPER_ADMIN);

        Cookie session = new Device(ADMIN_HOST).signIn(admin, true, "/admin");

        assertThat(session.getMaxAge()).isLessThan(0);
        assertThat(idleTimeoutOf(session)).isEqualTo(THIRTY_MINUTES);
    }

    @Test
    void signingOutStillEndsAKeptSession() throws Exception {
        User student = seedUser("kept-logout", Role.USER);
        Device phone = new Device(APP_HOST);
        phone.signIn(student, true, "/student/menu");

        phone.perform(post("/logout").with(csrf()));

        assertThat(phone.probe()).isEqualTo(302);
    }

    /** The case a 30-day session makes urgent: a manager switches off an operator's account. */
    @Test
    void switchingOffAnOperatorEndsTheSessionOnTheirPhone() throws Exception {
        User manager = seedUser("kept-manager", Role.CANTEEN_MANAGER);
        User operator = seedUser("kept-fired-operator", Role.CANTEEN_OPERATOR);
        Device operatorPhone = new Device(OUTLET_HOST);
        operatorPhone.signIn(operator, true, "/canteen/queue");
        assertThat(operatorPhone.probe()).isEqualTo(200);

        mockMvc.perform(post("/canteen/staff/{id}/status", operator.getId())
                        .header("Host", OUTLET_HOST)
                        .param("active", "false")
                        .with(csrf()).with(user(new AppUserPrincipal(manager))))
                .andExpect(redirectedUrl("/canteen/staff"));

        assertThat(operatorPhone.probe()).isEqualTo(302);
    }

    @Test
    void switchingOneOperatorOffLeavesTheirColleagueSignedIn() throws Exception {
        User manager = seedUser("kept-manager-2", Role.CANTEEN_MANAGER);
        User colleague = seedUser("kept-colleague", Role.CANTEEN_OPERATOR);
        User other = seedUser("kept-reinstated", Role.CANTEEN_OPERATOR);
        Device colleaguePhone = new Device(OUTLET_HOST);
        colleaguePhone.signIn(colleague, true, "/canteen/queue");

        mockMvc.perform(post("/canteen/staff/{id}/status", other.getId())
                        .header("Host", OUTLET_HOST)
                        .param("active", "false")
                        .with(csrf()).with(user(new AppUserPrincipal(manager))))
                .andExpect(status().is3xxRedirection());

        assertThat(colleaguePhone.probe()).isEqualTo(200);
    }
}
