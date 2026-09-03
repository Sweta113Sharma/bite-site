package com.bitesite.security;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.dao.OutletDao;
import com.bitesite.dao.UserDao;
import com.bitesite.model.Outlet;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import com.bitesite.service.EmailService;
import com.bitesite.service.SmsService;
import com.bitesite.tenant.Tenant;
import com.bitesite.tenant.TenantDao;
import com.bitesite.tenant.TenantStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end cover for everything a user can now do to their own account, driven over
 * real HTTP through the real filter chain: reset a forgotten password by email, change a
 * known one, sign other devices out, and edit their own details. None of it existed
 * before — a forgotten password meant the account was unreachable for good, no account of
 * any role could rotate its own credential, and a name or phone number typed at signup
 * was permanent.
 *
 * <p>{@link EmailService} is replaced with a mock so the 6-digit code can be captured
 * rather than sent. That is also the only way to read it: codes are stored SHA-256
 * hashed, which is the point of them. {@link SmsService} is mocked so the phone-change
 * path can be exercised on both sides of "is SMS even configured".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountSelfServiceFlowTest {

    private static final String APP_HOST = "app.localhost";
    private static final String OUTLET_HOST = "outlet.localhost";
    private static final String ADMIN_HOST = "admin.localhost";

    private static final String ORIGINAL_PASSWORD = "Original1";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantDao tenantDao;
    @Autowired private UserDao userDao;
    @Autowired private OutletDao outletDao;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockitoBean private EmailService emailService;
    @MockitoBean private SmsService smsService;

    private Tenant tenant;
    private Outlet outlet;
    private String runId;

    @BeforeAll
    void seedTenant() {
        runId = UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantDao.save(Tenant.builder().name("Recovery Test College " + runId)
                .status(TenantStatus.ACTIVE).build());
        outlet = outletDao.save(Outlet.builder().tenantId(tenant.getId())
                .name("Recovery Test Canteen " + runId).active(true).build());
    }

    @BeforeEach
    void smtpIsAvailable() {
        // Mockito resets bean overrides between methods, so this cannot live in @BeforeAll.
        when(emailService.isConfigured()).thenReturn(true);
    }

    /**
     * A browser-shaped client: one source IP, one cookie jar.
     *
     * <p>Both halves are load-bearing. Sessions are Spring Session JDBC, so the recovery
     * flow's session marker lives behind the {@code SESSION} cookie rather than in a
     * {@code MockHttpSession} a test can hand over — without carrying cookies forward,
     * step two of every flow arrives looking like a brand-new visitor. And the login
     * throttle is IP-keyed at ten attempts per five minutes, so tests all sharing
     * 127.0.0.1 would eventually start rate-limiting each other.
     */
    private final class Client {

        private final String remoteAddr = "10.%d.%d.%d".formatted(
                ThreadLocalRandom.current().nextInt(1, 255),
                ThreadLocalRandom.current().nextInt(1, 255),
                ThreadLocalRandom.current().nextInt(1, 255));

        private Cookie[] cookies = new Cookie[0];

        ResultActions perform(MockHttpServletRequestBuilder builder, String host) throws Exception {
            builder.header("Host", host).remoteAddress(remoteAddr);
            if (cookies.length > 0) {
                builder.cookie(cookies);
            }
            ResultActions actions = mockMvc.perform(builder);
            MvcResult result = actions.andReturn();
            Cookie[] returned = result.getResponse().getCookies();
            if (returned != null && returned.length > 0) {
                cookies = returned;
            }
            return actions;
        }

        ResultActions perform(MockHttpServletRequestBuilder builder) throws Exception {
            return perform(builder, APP_HOST);
        }
    }

    private User seedUser(String label, Role role) {
        boolean platform = role == Role.SUPER_ADMIN || role == Role.TECH_MANAGER;
        boolean outletRole = role == Role.CANTEEN_MANAGER || role == Role.CANTEEN_OPERATOR;
        return userDao.save(User.builder()
                .tenantId(platform ? null : tenant.getId())
                .outletId(outletRole ? outlet.getId() : null)
                .name(label)
                .email(label + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local")
                .passwordHash(passwordEncoder.encode(ORIGINAL_PASSWORD))
                .role(role).activeRole(role).active(true)
                .emailVerified(true).phoneVerified(true)
                .build());
    }

    /** Runs the "email me a code" step and returns the code that would have been sent. */
    private String requestCode(String email, Client client) throws Exception {
        client.perform(post("/forgot-password").with(csrf()).param("email", email))
                .andExpect(redirectedUrl("/reset-password"));

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPasswordResetEmail(eq(email), anyString(), code.capture());
        return code.getValue();
    }

    /** Asserts a sign-in succeeded the way the rest of this package does: by where the
     * success handler sent them. A student lands on the menu. */
    private void assertSignsIn(String email, String password) throws Exception {
        new Client().perform(post("/login").with(csrf())
                        .param("username", email).param("password", password))
                .andExpect(redirectedUrl("/student/menu"));
    }

    private void assertCannotSignIn(String email, String password) throws Exception {
        new Client().perform(post("/login").with(csrf())
                        .param("username", email).param("password", password))
                .andExpect(redirectedUrl("/login?error"));
    }

    // ---------- Reset by email ----------

    @Test
    void aLockedOutUserCanSetANewPasswordAndSignInWithIt() throws Exception {
        User student = seedUser("reset-student", Role.USER);
        Client client = new Client();

        String code = requestCode(student.getEmail(), client);
        assertThat(code).matches("\\d{6}");

        client.perform(post("/reset-password").with(csrf())
                        .param("code", code)
                        .param("newPassword", "Recovered1")
                        .param("confirmPassword", "Recovered1"))
                .andExpect(redirectedUrl("/login?passwordReset"));

        assertSignsIn(student.getEmail(), "Recovered1");
    }

    @Test
    void theOldPasswordStopsWorkingAfterAReset() throws Exception {
        User student = seedUser("reset-old-pw", Role.USER);
        Client client = new Client();

        String code = requestCode(student.getEmail(), client);
        client.perform(post("/reset-password").with(csrf())
                        .param("code", code)
                        .param("newPassword", "Recovered1")
                        .param("confirmPassword", "Recovered1"))
                .andExpect(redirectedUrl("/login?passwordReset"));

        assertCannotSignIn(student.getEmail(), ORIGINAL_PASSWORD);
    }

    @Test
    void aResetCodeCannotBeSpentTwice() throws Exception {
        User student = seedUser("reset-replay", Role.USER);
        Client client = new Client();

        String code = requestCode(student.getEmail(), client);
        client.perform(post("/reset-password").with(csrf())
                        .param("code", code)
                        .param("newPassword", "Recovered1")
                        .param("confirmPassword", "Recovered1"))
                .andExpect(redirectedUrl("/login?passwordReset"));

        // The session marker is cleared on success, so a replay can no longer even name
        // an account — and the code itself was consumed regardless.
        client.perform(post("/reset-password").with(csrf())
                        .param("code", code)
                        .param("newPassword", "Attacker11")
                        .param("confirmPassword", "Attacker11"))
                .andExpect(redirectedUrl("/forgot-password"));

        assertCannotSignIn(student.getEmail(), "Attacker11");
    }

    @Test
    void aWrongCodeIsRejectedWithoutChangingThePassword() throws Exception {
        User student = seedUser("reset-wrong-code", Role.USER);
        Client client = new Client();

        String code = requestCode(student.getEmail(), client);
        String wrong = code.equals("000000") ? "111111" : "000000";

        client.perform(post("/reset-password").with(csrf())
                        .param("code", wrong)
                        .param("newPassword", "Attacker11")
                        .param("confirmPassword", "Attacker11"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("codeError"));

        assertSignsIn(student.getEmail(), ORIGINAL_PASSWORD);
    }

    @Test
    void aMistypedConfirmationIsCaughtBeforeAnythingChanges() throws Exception {
        User student = seedUser("reset-mismatch", Role.USER);
        Client client = new Client();
        String code = requestCode(student.getEmail(), client);

        client.perform(post("/reset-password").with(csrf())
                        .param("code", code)
                        .param("newPassword", "Recovered1")
                        .param("confirmPassword", "Recovered2"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "confirmPassword"));

        assertSignsIn(student.getEmail(), ORIGINAL_PASSWORD);
    }

    @Test
    void aWeakNewPasswordIsRefused() throws Exception {
        User student = seedUser("reset-weak", Role.USER);
        Client client = new Client();
        String code = requestCode(student.getEmail(), client);

        client.perform(post("/reset-password").with(csrf())
                        .param("code", code)
                        .param("newPassword", "short")
                        .param("confirmPassword", "short"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "newPassword"));
    }

    /**
     * The whole point of the session-marker design: an address with no account has to be
     * indistinguishable from one that has an account, all the way to the rendered page.
     */
    @Test
    void anUnknownEmailIsIndistinguishableFromARegisteredOne() throws Exception {
        User student = seedUser("reset-enum", Role.USER);
        String unknownEmail = "nobody-" + runId + "@test.local";

        Client known = new Client();
        MvcResult knownResult = known.perform(post("/forgot-password").with(csrf())
                        .param("email", student.getEmail()))
                .andExpect(redirectedUrl("/reset-password")).andReturn();

        Client unknown = new Client();
        MvcResult unknownResult = unknown.perform(post("/forgot-password").with(csrf())
                        .param("email", unknownEmail))
                .andExpect(redirectedUrl("/reset-password")).andReturn();

        assertThat(unknownResult.getResponse().getStatus())
                .isEqualTo(knownResult.getResponse().getStatus());

        // And both land on the same page — not a bounce back to the lookup form, which is
        // what would give the answer away.
        known.perform(get("/reset-password")).andExpect(status().isOk());
        unknown.perform(get("/reset-password")).andExpect(status().isOk());

        verify(emailService, never()).sendPasswordResetEmail(eq(unknownEmail), anyString(), anyString());
    }

    @Test
    void recoveryPagesAreReachableWithoutSigningIn() throws Exception {
        new Client().perform(get("/forgot-password")).andExpect(status().isOk());
        // Arriving at /reset-password with no request behind it has nothing to reset.
        new Client().perform(get("/reset-password")).andExpect(redirectedUrl("/forgot-password"));
    }

    @Test
    void aDeactivatedAccountIsNotSentAResetCode() throws Exception {
        User student = seedUser("reset-inactive", Role.USER);
        userDao.setActive(student.getId(), false);

        new Client().perform(post("/forgot-password").with(csrf()).param("email", student.getEmail()))
                .andExpect(redirectedUrl("/reset-password"));

        verify(emailService, never()).sendPasswordResetEmail(eq(student.getEmail()), anyString(), anyString());
    }

    // ---------- Change password while signed in ----------

    @Test
    void aSignedInUserCanChangeTheirPasswordAndTheNewOneWorks() throws Exception {
        User student = seedUser("change-student", Role.USER);

        new Client().perform(post("/account/password").with(csrf())
                        .with(user(new AppUserPrincipal(student)))
                        .param("currentPassword", ORIGINAL_PASSWORD)
                        .param("newPassword", "Changed12")
                        .param("confirmPassword", "Changed12"))
                .andExpect(redirectedUrl("/account/password"));

        assertSignsIn(student.getEmail(), "Changed12");
        assertCannotSignIn(student.getEmail(), ORIGINAL_PASSWORD);
    }

    @Test
    void changingAPasswordRequiresTheCurrentOne() throws Exception {
        User student = seedUser("change-wrong-current", Role.USER);

        new Client().perform(post("/account/password").with(csrf())
                        .with(user(new AppUserPrincipal(student)))
                        .param("currentPassword", "NotTheOne1")
                        .param("newPassword", "Changed12")
                        .param("confirmPassword", "Changed12"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "currentPassword"));

        assertCannotSignIn(student.getEmail(), "Changed12");
        assertSignsIn(student.getEmail(), ORIGINAL_PASSWORD);
    }

    @Test
    void changingAPasswordRefusesTheSameOneAgain() throws Exception {
        User student = seedUser("change-same", Role.USER);

        new Client().perform(post("/account/password").with(csrf())
                        .with(user(new AppUserPrincipal(student)))
                        .param("currentPassword", ORIGINAL_PASSWORD)
                        .param("newPassword", ORIGINAL_PASSWORD)
                        .param("confirmPassword", ORIGINAL_PASSWORD))
                .andExpect(status().isOk())
                // Under the field it is about, not under "current password".
                .andExpect(model().attributeHasFieldErrors("form", "newPassword"));
    }

    @Test
    void changePasswordIsNotReachableAnonymously() throws Exception {
        new Client().perform(get("/account/password")).andExpect(status().is3xxRedirection());
    }

    /**
     * The reason the page lives at {@code /account/**} rather than under one portal: it
     * has to work for whoever is signed in, wherever they are. A route under
     * {@code /student} would have left staff and admins with no way to rotate a
     * credential at all — which is exactly the state this found the product in.
     */
    @Test
    void changePasswordIsReachableFromEveryPortal() throws Exception {
        new Client().perform(get("/account/password")
                .with(user(new AppUserPrincipal(seedUser("portal-student", Role.USER)))), APP_HOST)
                .andExpect(status().isOk());
        new Client().perform(get("/account/password")
                .with(user(new AppUserPrincipal(seedUser("portal-manager", Role.CANTEEN_MANAGER)))), OUTLET_HOST)
                .andExpect(status().isOk());
        new Client().perform(get("/account/password")
                .with(user(new AppUserPrincipal(seedUser("portal-admin", Role.SUPER_ADMIN)))), ADMIN_HOST)
                .andExpect(status().isOk());
        new Client().perform(get("/account/password")
                .with(user(new AppUserPrincipal(seedUser("portal-tech", Role.TECH_MANAGER)))), ADMIN_HOST)
                .andExpect(status().isOk());
    }

    // ---------- Admin-initiated reset ----------

    @Test
    void aSuperAdminCanSendAResetCodeToAPlatformAccount() throws Exception {
        User admin = seedUser("initiator-admin", Role.SUPER_ADMIN);
        User target = seedUser("initiated-target", Role.TECH_MANAGER);

        new Client().perform(post("/admin/users/{id}/password-reset", target.getId())
                        .with(csrf()).with(user(new AppUserPrincipal(admin))), ADMIN_HOST)
                .andExpect(redirectedUrl("/admin/users"));

        verify(emailService).sendPasswordResetEmail(eq(target.getEmail()), anyString(), anyString());
    }

    @Test
    void aTechManagerCannotSendResetCodesToPlatformAccounts() throws Exception {
        User tech = seedUser("initiator-tech", Role.TECH_MANAGER);
        User target = seedUser("initiated-target-2", Role.SUPER_ADMIN);

        new Client().perform(post("/admin/users/{id}/password-reset", target.getId())
                        .with(csrf()).with(user(new AppUserPrincipal(tech))), ADMIN_HOST)
                .andExpect(status().isForbidden());

        verify(emailService, never()).sendPasswordResetEmail(eq(target.getEmail()), anyString(), anyString());
    }

    /** Platform accounts are the ones with no tenant; a student has one. Pointing the
     * platform endpoint at a student must not mail them anything. */
    @Test
    void theAdminResetEndpointRefusesANonPlatformAccount() throws Exception {
        User admin = seedUser("initiator-admin-2", Role.SUPER_ADMIN);
        User student = seedUser("initiated-student", Role.USER);

        new Client().perform(post("/admin/users/{id}/password-reset", student.getId())
                        .with(csrf()).with(user(new AppUserPrincipal(admin))), ADMIN_HOST)
                .andExpect(status().isNotFound());

        verify(emailService, never()).sendPasswordResetEmail(eq(student.getEmail()), anyString(), anyString());
    }

    // ---------- Other sessions ----------

    /** Signs in and returns a client holding that session. */
    private Client signedInClient(User user, String password) throws Exception {
        Client client = new Client();
        client.perform(post("/login").with(csrf())
                        .param("username", user.getEmail()).param("password", password))
                .andExpect(redirectedUrl("/student/menu"));
        // Portal-independent liveness probe: 200 while the session holds, a redirect to
        // the login page once it does not.
        client.perform(get("/account/password")).andExpect(status().isOk());
        return client;
    }

    @Test
    void changingThePasswordSignsOtherDevicesOutButNotThisOne() throws Exception {
        User student = seedUser("sessions-change", Role.USER);
        Client phone = signedInClient(student, ORIGINAL_PASSWORD);
        Client laptop = signedInClient(student, ORIGINAL_PASSWORD);

        laptop.perform(post("/account/password").with(csrf())
                        .param("currentPassword", ORIGINAL_PASSWORD)
                        .param("newPassword", "Changed12")
                        .param("confirmPassword", "Changed12"))
                .andExpect(redirectedUrl("/account/password"));

        // The device that did the changing stays where it is...
        laptop.perform(get("/account/password")).andExpect(status().isOk());
        // ...and the one still holding a session opened with the old password does not.
        phone.perform(get("/account/password")).andExpect(status().is3xxRedirection());
    }

    @Test
    void signOutOtherDevicesEndsTheOthersOnly() throws Exception {
        User student = seedUser("sessions-revoke", Role.USER);
        Client phone = signedInClient(student, ORIGINAL_PASSWORD);
        Client laptop = signedInClient(student, ORIGINAL_PASSWORD);

        laptop.perform(post("/account/sessions/revoke-others").with(csrf()))
                .andExpect(redirectedUrl("/account/password"));

        laptop.perform(get("/account/password")).andExpect(status().isOk());
        phone.perform(get("/account/password")).andExpect(status().is3xxRedirection());
    }

    @Test
    void oneUsersSessionRevocationDoesNotTouchAnother() throws Exception {
        User mine = seedUser("sessions-mine", Role.USER);
        User theirs = seedUser("sessions-theirs", Role.USER);
        Client myLaptop = signedInClient(mine, ORIGINAL_PASSWORD);
        Client theirPhone = signedInClient(theirs, ORIGINAL_PASSWORD);

        myLaptop.perform(post("/account/sessions/revoke-others").with(csrf()))
                .andExpect(redirectedUrl("/account/password"));

        theirPhone.perform(get("/account/password")).andExpect(status().isOk());
    }

    // ---------- Profile ----------

    @Test
    void aUserCanFixTheirOwnName() throws Exception {
        User student = seedUser("profile-name", Role.USER);

        new Client().perform(post("/account/profile").with(csrf())
                        .with(user(new AppUserPrincipal(student)))
                        .param("name", "Corrected Name")
                        .param("phone", "")
                        .param("rollNo", "CS-2024-011"))
                .andExpect(redirectedUrl("/account/profile"));

        User reloaded = userDao.findById(student.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Corrected Name");
        assertThat(reloaded.getRollNo()).isEqualTo("CS-2024-011");
    }

    @Test
    void aMalformedPhoneNumberIsRefused() throws Exception {
        User student = seedUser("profile-bad-phone", Role.USER);

        new Client().perform(post("/account/profile").with(csrf())
                        .with(user(new AppUserPrincipal(student)))
                        .param("name", "A Student")
                        .param("phone", "12345")
                        .param("rollNo", ""))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "phone"));

        assertThat(userDao.findById(student.getId()).orElseThrow().getName()).isEqualTo("profile-bad-phone");
    }

    @Test
    void aBlankNameIsRefused() throws Exception {
        User student = seedUser("profile-blank-name", Role.USER);

        new Client().perform(post("/account/profile").with(csrf())
                        .with(user(new AppUserPrincipal(student)))
                        .param("name", "  ")
                        .param("phone", "")
                        .param("rollNo", ""))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "name"));
    }

    /**
     * A new number has not been proved. With SMS available the user is handed straight to
     * the OTP screen — leaving the flag unset would instead surface as a locked account at
     * their next sign-in, long after they had forgotten changing it.
     */
    @Test
    void changingThePhoneNumberSendsTheUserToVerifyItWhenSmsIsAvailable() throws Exception {
        when(smsService.isConfigured()).thenReturn(true);
        User student = seedUser("profile-phone-verify", Role.USER);

        new Client().perform(post("/account/profile").with(csrf())
                        .with(user(new AppUserPrincipal(student)))
                        .param("name", "A Student")
                        .param("phone", "9876543210")
                        .param("rollNo", ""))
                .andExpect(redirectedUrl("/verify"));

        User reloaded = userDao.findById(student.getId()).orElseThrow();
        assertThat(reloaded.getPhone()).isEqualTo("9876543210");
        assertThat(reloaded.isPhoneVerified()).isFalse();
        verify(smsService).sendOtp(eq("9876543210"), anyString());
    }

    /** Mirrors registerStudent: with no way to deliver a code, gating on one would lock
     * people out of an account they can otherwise use. */
    @Test
    void changingThePhoneNumberDoesNotGateTheAccountWhenSmsIsNotConfigured() throws Exception {
        when(smsService.isConfigured()).thenReturn(false);
        User student = seedUser("profile-phone-nosms", Role.USER);

        new Client().perform(post("/account/profile").with(csrf())
                        .with(user(new AppUserPrincipal(student)))
                        .param("name", "A Student")
                        .param("phone", "9876543210")
                        .param("rollNo", ""))
                .andExpect(redirectedUrl("/account/profile"));

        User reloaded = userDao.findById(student.getId()).orElseThrow();
        assertThat(reloaded.getPhone()).isEqualTo("9876543210");
        assertThat(reloaded.isPhoneVerified()).isTrue();
    }

    /** Editing the name must not disturb a phone verification that is still outstanding. */
    @Test
    void leavingThePhoneUntouchedDoesNotMarkAnUnverifiedNumberVerified() throws Exception {
        when(smsService.isConfigured()).thenReturn(true);
        User student = userDao.save(User.builder()
                .tenantId(tenant.getId()).name("Half Signed Up")
                .email("profile-pending-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local")
                .passwordHash(passwordEncoder.encode(ORIGINAL_PASSWORD))
                .phone("9000000001")
                .role(Role.USER).activeRole(Role.USER).active(true)
                .emailVerified(true).phoneVerified(false)
                .build());

        new Client().perform(post("/account/profile").with(csrf())
                        .with(user(new AppUserPrincipal(student)))
                        .param("name", "Renamed")
                        .param("phone", "9000000001")
                        .param("rollNo", ""))
                .andExpect(redirectedUrl("/account/profile"));

        User reloaded = userDao.findById(student.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Renamed");
        assertThat(reloaded.isPhoneVerified()).isFalse();
    }

    @Test
    void theProfilePageIsReachableFromEveryPortal() throws Exception {
        new Client().perform(get("/account/profile")
                .with(user(new AppUserPrincipal(seedUser("prof-student", Role.USER)))), APP_HOST)
                .andExpect(status().isOk());
        new Client().perform(get("/account/profile")
                .with(user(new AppUserPrincipal(seedUser("prof-manager", Role.CANTEEN_MANAGER)))), OUTLET_HOST)
                .andExpect(status().isOk());
        new Client().perform(get("/account/profile")
                .with(user(new AppUserPrincipal(seedUser("prof-admin", Role.SUPER_ADMIN)))), ADMIN_HOST)
                .andExpect(status().isOk());
    }

    @Test
    void theProfilePageIsNotReachableAnonymously() throws Exception {
        new Client().perform(get("/account/profile")).andExpect(status().is3xxRedirection());
    }

    // ---------- The pages these changes touched actually render ----------

    /**
     * A Thymeleaf template that does not parse fails at render time, not at build time, so
     * a mistyped attribute in a page nothing exercises ships silently. These four were
     * edited to add the new entry points; rendering them is what proves the edits are
     * well-formed.
     */
    @Test
    void theEditedCustomerAndOutletPagesStillRender() throws Exception {
        new Client().perform(get("/login")).andExpect(status().isOk());
        new Client().perform(get("/student/account")
                .with(user(new AppUserPrincipal(seedUser("render-student", Role.USER)))), APP_HOST)
                .andExpect(status().isOk());

        // One client for the whole sweep. A fresh one per page means a fresh server-side
        // session per page, and Spring Session's expiry job contends with that many
        // inserts on SPRING_SESSION_ATTRIBUTES.
        AppUserPrincipal manager = new AppUserPrincipal(seedUser("render-manager", Role.CANTEEN_MANAGER));
        Client client = new Client();
        for (String path : new String[] {"/canteen/staff", "/canteen/categories", "/canteen/queue",
                                         "/canteen/menu", "/canteen/orders", "/canteen/settings"}) {
            client.perform(get(path).with(user(manager)), OUTLET_HOST)
                    .andExpect(status().isOk());
        }
    }

    /**
     * Every console screen, rendered.
     *
     * <p>The page headers, empty states and table wrappers across this portal were changed
     * in one sweep. A Thymeleaf template only fails when something renders it, so a sweep
     * with no test behind it ships a 500 on whichever screen nobody happened to open —
     * and several of these are screens you visit once a term.
     */
    @Test
    void everyAdminAndTechManagerPageRenders() throws Exception {
        AppUserPrincipal admin = new AppUserPrincipal(seedUser("render-admin", Role.SUPER_ADMIN));
        Client client = new Client();
        for (String path : new String[] {
                "/admin/tenants", "/admin/tenants/new", "/admin/tenants/" + tenant.getId(),
                "/admin/users", "/admin/audit-log", "/admin/grievances", "/admin/orders",
                "/admin/support", "/admin/outlets", "/admin/payments", "/admin/dpdp",
                "/admin/onboarding", "/admin/onboarding/new",
                "/techmgr", "/techmgr/health", "/techmgr/tenants/" + tenant.getId()}) {
            client.perform(get(path).with(user(admin)), ADMIN_HOST)
                    .andExpect(status().isOk());
        }
    }

    /** The audit log opened on a blank page when no college was chosen. Both states now
     * say something, and both have to render. */
    @Test
    void theAuditLogSaysSomethingWithAndWithoutACollegeChosen() throws Exception {
        AppUserPrincipal admin = new AppUserPrincipal(seedUser("render-audit-admin", Role.SUPER_ADMIN));
        Client client = new Client();

        client.perform(get("/admin/audit-log").with(user(admin)), ADMIN_HOST)
                .andExpect(status().isOk());
        client.perform(get("/admin/audit-log").param("tenantId", String.valueOf(tenant.getId()))
                        .with(user(admin)), ADMIN_HOST)
                .andExpect(status().isOk());
    }

    @Test
    void aManagerCanSendAResetCodeToTheirOwnOutletsStaff() throws Exception {
        User manager = seedUser("outlet-manager", Role.CANTEEN_MANAGER);
        User cook = seedUser("outlet-cook", Role.CANTEEN_OPERATOR);

        new Client().perform(post("/canteen/staff/{id}/password-reset", cook.getId())
                        .with(csrf()).with(user(new AppUserPrincipal(manager))), OUTLET_HOST)
                .andExpect(redirectedUrl("/canteen/staff"));

        verify(emailService).sendPasswordResetEmail(eq(cook.getEmail()), anyString(), anyString());
    }

    /** The outlet id comes from the signed-in manager, never the request, so a crafted id
     * cannot reach across to another canteen's account. */
    @Test
    void aManagerCannotSendAResetCodeToAnotherOutletsStaff() throws Exception {
        User manager = seedUser("outlet-manager-2", Role.CANTEEN_MANAGER);
        Outlet otherOutlet = outletDao.save(Outlet.builder().tenantId(tenant.getId())
                .name("Other Canteen " + UUID.randomUUID().toString().substring(0, 8)).active(true).build());
        User theirCook = userDao.save(User.builder()
                .tenantId(tenant.getId()).outletId(otherOutlet.getId()).name("Their Cook")
                .email("other-cook-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local")
                .passwordHash(passwordEncoder.encode(ORIGINAL_PASSWORD))
                .role(Role.CANTEEN_OPERATOR).activeRole(Role.CANTEEN_OPERATOR).active(true)
                .emailVerified(true).phoneVerified(true).build());

        new Client().perform(post("/canteen/staff/{id}/password-reset", theirCook.getId())
                        .with(csrf()).with(user(new AppUserPrincipal(manager))), OUTLET_HOST)
                .andExpect(status().isNotFound());

        verify(emailService, never()).sendPasswordResetEmail(eq(theirCook.getEmail()), anyString(), anyString());
    }

    /** An operator is not a manager: the staff screen and everything on it is manager-only. */
    @Test
    void anOperatorCannotSendResetCodes() throws Exception {
        User operator = seedUser("outlet-operator", Role.CANTEEN_OPERATOR);
        User cook = seedUser("outlet-cook-2", Role.CANTEEN_OPERATOR);

        new Client().perform(post("/canteen/staff/{id}/password-reset", cook.getId())
                        .with(csrf()).with(user(new AppUserPrincipal(operator))), OUTLET_HOST)
                .andExpect(status().isForbidden());

        verify(emailService, never()).sendPasswordResetEmail(eq(cook.getEmail()), anyString(), anyString());
    }

    // ---------- Admin-initiated reset, from the college screen ----------

    @Test
    void aSuperAdminCanResetAnOutletAccountFromTheCollegeScreen() throws Exception {
        User admin = seedUser("tenant-reset-admin", Role.SUPER_ADMIN);
        User manager = seedUser("tenant-reset-manager", Role.CANTEEN_MANAGER);

        new Client().perform(post("/admin/tenants/{id}/staff/{userId}/password-reset",
                        tenant.getId(), manager.getId())
                        .with(csrf()).with(user(new AppUserPrincipal(admin))), ADMIN_HOST)
                .andExpect(redirectedUrl("/admin/tenants/" + tenant.getId()));

        verify(emailService).sendPasswordResetEmail(eq(manager.getEmail()), anyString(), anyString());
    }

    /** The screen is scoped to one college, and so is the action behind it. */
    @Test
    void theCollegeScreenCannotResetAnAccountAtAnotherCollege() throws Exception {
        User admin = seedUser("tenant-reset-admin-2", Role.SUPER_ADMIN);
        Tenant otherCollege = tenantDao.save(Tenant.builder()
                .name("Other College " + UUID.randomUUID().toString().substring(0, 8))
                .status(TenantStatus.ACTIVE).build());
        User theirManager = userDao.save(User.builder()
                .tenantId(otherCollege.getId()).outletId(null).name("Their Manager")
                .email("other-mgr-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local")
                .passwordHash(passwordEncoder.encode(ORIGINAL_PASSWORD))
                .role(Role.CANTEEN_MANAGER).activeRole(Role.CANTEEN_MANAGER).active(true)
                .emailVerified(true).phoneVerified(true).build());

        new Client().perform(post("/admin/tenants/{id}/staff/{userId}/password-reset",
                        tenant.getId(), theirManager.getId())
                        .with(csrf()).with(user(new AppUserPrincipal(admin))), ADMIN_HOST)
                .andExpect(status().isNotFound());

        verify(emailService, never()).sendPasswordResetEmail(eq(theirManager.getEmail()), anyString(), anyString());
    }

    /** A student belongs to the college too, but is not staff — this endpoint is on the
     * staff card and must not become a way to mail students from the admin console. */
    @Test
    void theCollegeScreenRefusesAStudentAccount() throws Exception {
        User admin = seedUser("tenant-reset-admin-3", Role.SUPER_ADMIN);
        User student = seedUser("tenant-reset-student", Role.USER);

        new Client().perform(post("/admin/tenants/{id}/staff/{userId}/password-reset",
                        tenant.getId(), student.getId())
                        .with(csrf()).with(user(new AppUserPrincipal(admin))), ADMIN_HOST)
                .andExpect(status().isNotFound());

        verify(emailService, never()).sendPasswordResetEmail(eq(student.getEmail()), anyString(), anyString());
    }

    @Test
    void aTechManagerCannotResetOutletAccountsFromTheCollegeScreen() throws Exception {
        User tech = seedUser("tenant-reset-tech", Role.TECH_MANAGER);
        User manager = seedUser("tenant-reset-manager-2", Role.CANTEEN_MANAGER);

        new Client().perform(post("/admin/tenants/{id}/staff/{userId}/password-reset",
                        tenant.getId(), manager.getId())
                        .with(csrf()).with(user(new AppUserPrincipal(tech))), ADMIN_HOST)
                .andExpect(status().isForbidden());

        verify(emailService, never()).sendPasswordResetEmail(eq(manager.getEmail()), anyString(), anyString());
    }
}
