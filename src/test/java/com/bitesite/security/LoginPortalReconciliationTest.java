package com.bitesite.security;

import com.bitesite.dao.UserDao;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the login success handler reconciles {@code active_role} against the portal
 * being logged into, rather than blindly trusting whatever was last persisted (which may
 * be from a session on a completely different portal). This is what makes "one account,
 * multiple roles, log in on every portal you're eligible for" actually work end to end —
 * not just at the data-model level.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginPortalReconciliationTest {

    private static final String APP_HOST = "app.localhost";
    private static final String OUTLET_HOST = "outlet.localhost";
    private static final String ADMIN_HOST = "admin.localhost";
    private static final String PASSWORD = "Password1!";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantDao tenantDao;
    @Autowired private UserDao userDao;
    @Autowired private PasswordEncoder passwordEncoder;

    private String runId;
    // Unique per test run so this class's real POST /login calls never share a rate-limit
    // bucket (LoginRateLimitFilter keys on remote address) with other test classes or with
    // a previous run of this same suite — that bucket lives in a real DB table and isn't
    // reset between runs.
    private String fakeRemoteAddr;

    @BeforeAll
    void setUp() {
        runId = java.util.UUID.randomUUID().toString().substring(0, 8);
        fakeRemoteAddr = "10.%d.%d.%d".formatted(
                ThreadLocalRandom.current().nextInt(1, 255),
                ThreadLocalRandom.current().nextInt(1, 255),
                ThreadLocalRandom.current().nextInt(1, 255));
        tenantDao.save(Tenant.builder().name("Login Reconcile College " + runId)
                .status(TenantStatus.ACTIVE).build());
    }

    private MockHttpServletRequestBuilder loginRequest(String host, User user) {
        return post("/login").with(csrf())
                .header("Host", host)
                .remoteAddress(fakeRemoteAddr)
                .param("username", user.getEmail())
                .param("password", PASSWORD);
    }

    private User seed(String emailPrefix, Role primary, Set<Role> extraRoles, Role persistedActiveRole) {
        User user = userDao.save(User.builder().name("Test")
                .email(emailPrefix + "-" + runId + "@test.local")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .role(primary).activeRole(persistedActiveRole).active(true).emailVerified(true).build());
        for (Role r : extraRoles) {
            userDao.grantRole(user.getId(), r, null);
        }
        return user;
    }

    @Test
    void multiRoleUserLoggingIntoAdminPortalGetsSwitchedToAnEligibleRole() throws Exception {
        // Holds CANTEEN_STAFF (persisted active) + SUPER_ADMIN, but logs in on admin.localhost.
        User user = seed("multi-admin", Role.CANTEEN_STAFF, Set.of(Role.SUPER_ADMIN), Role.CANTEEN_STAFF);

        mockMvc.perform(loginRequest(ADMIN_HOST, user))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/admin"));

        User reloaded = userDao.findByEmail(user.getEmail()).orElseThrow();
        assertThat(reloaded.getActiveRole()).isEqualTo(Role.SUPER_ADMIN);
    }

    @Test
    void userWhoseActiveRoleAlreadyFitsThePortalKeepsIt() throws Exception {
        User user = seed("fits-outlet", Role.CANTEEN_STAFF, Set.of(), Role.CANTEEN_STAFF);

        mockMvc.perform(loginRequest(OUTLET_HOST, user))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/canteen/queue"));

        User reloaded = userDao.findByEmail(user.getEmail()).orElseThrow();
        assertThat(reloaded.getActiveRole()).isEqualTo(Role.CANTEEN_STAFF);
    }

    @Test
    void userWithNoRoleForThisPortalIsRejectedNotSilentlyLoggedIn() throws Exception {
        // Only holds USER (app portal) — tries to log in on the outlet portal.
        User user = seed("no-access", Role.USER, Set.of(), Role.USER);

        mockMvc.perform(loginRequest(OUTLET_HOST, user))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/login?error=noaccess"));

        User reloaded = userDao.findByEmail(user.getEmail()).orElseThrow();
        assertThat(reloaded.getActiveRole()).isEqualTo(Role.USER); // unchanged — login was rejected
    }

    @Test
    void multiRoleUserCanLogInOnEachEligiblePortalInTurn() throws Exception {
        User user = seed("all-portals", Role.USER,
                Set.of(Role.CANTEEN_STAFF, Role.SUPER_ADMIN), Role.USER);

        mockMvc.perform(loginRequest(APP_HOST, user))
                .andExpect(header().string("Location", "/student/menu"));

        mockMvc.perform(loginRequest(OUTLET_HOST, user))
                .andExpect(header().string("Location", "/canteen/queue"));

        mockMvc.perform(loginRequest(ADMIN_HOST, user))
                .andExpect(header().string("Location", "/admin"));
    }

    @Test
    void reconciledActiveRoleSurvivesIntoTheNextRequestOnTheSameSession() throws Exception {
        // Regression test: updating SecurityContextHolder mid-request without explicitly
        // saving it to the SecurityContextRepository only affects the current
        // response — the DB's active_role was correct but the *session* still carried the
        // old, pre-reconciliation authority, so the very next request 403'd.
        User user = seed("survives-next-req", Role.CANTEEN_STAFF, Set.of(Role.SUPER_ADMIN), Role.CANTEEN_STAFF);

        MvcResult loginResult = mockMvc.perform(loginRequest(ADMIN_HOST, user))
                .andExpect(header().string("Location", "/admin"))
                .andReturn();
        Cookie sessionCookie = sessionCookie(loginResult);

        mockMvc.perform(get("/admin/tenants").header("Host", ADMIN_HOST).cookie(sessionCookie))
                .andExpect(status().isOk());
    }

    @Test
    void roleSwitchSurvivesIntoTheNextRequestOnTheSameSession() throws Exception {
        User user = seed("switch-survives", Role.CANTEEN_STAFF,
                Set.of(Role.SUPER_ADMIN, Role.TECH_MANAGER), Role.SUPER_ADMIN);

        MvcResult loginResult = mockMvc.perform(loginRequest(ADMIN_HOST, user))
                .andExpect(header().string("Location", "/admin"))
                .andReturn();
        Cookie sessionCookie = sessionCookie(loginResult);

        mockMvc.perform(post("/api/role/switch").with(csrf())
                        .header("Host", ADMIN_HOST).cookie(sessionCookie)
                        .param("role", "TECH_MANAGER"))
                .andExpect(header().string("Location", "/techmgr"));

        // TECH_MANAGER can reach an OPS_SCOPE route but not a FULL_ADMIN (SUPER_ADMIN-only)
        // one — proves the session now genuinely carries TECH_MANAGER, not a leftover
        // SUPER_ADMIN authority from before the switch.
        mockMvc.perform(get("/admin/tenants").header("Host", ADMIN_HOST).cookie(sessionCookie))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/grievances").header("Host", ADMIN_HOST).cookie(sessionCookie))
                .andExpect(status().isOk());
    }

    // Spring Session JDBC resolves sessions via the SESSION cookie, not the servlet
    // container's built-in session mechanism — so reusing a session across MockMvc calls
    // means carrying this cookie forward, not MockHttpServletRequestBuilder#session(...).
    private Cookie sessionCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("SESSION");
        assertThat(cookie).isNotNull();
        return cookie;
    }
}
