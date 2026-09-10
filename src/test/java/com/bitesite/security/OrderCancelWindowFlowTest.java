package com.bitesite.security;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.dao.OrderDao;
import com.bitesite.dao.OutletDao;
import com.bitesite.dao.PlatformSettingsDao;
import com.bitesite.dao.UserDao;
import com.bitesite.model.Order;
import com.bitesite.model.OrderSettings;
import com.bitesite.model.OrderStatus;
import com.bitesite.model.Outlet;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import com.bitesite.service.OrderService;
import com.bitesite.tenant.Tenant;
import com.bitesite.tenant.TenantDao;
import com.bitesite.tenant.TenantStatus;
import org.junit.jupiter.api.AfterAll;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The cancellation window, end to end: set from the admin console, read by the order path,
 * and enforced by MySQL's clock.
 *
 * <p>Two things are proved here that a mocked test cannot prove. First, that the number an
 * admin types actually reaches {@link OrderService} rather than a compiled-in constant.
 * Second, that the two halves of the window are exact complements against a real database:
 * every paid order is either cancellable by the student or visible to the kitchen, never
 * both and never neither. That second property is the one holding the money safe, and it
 * lives in SQL, so it is tested in SQL.
 *
 * <p>paid_at is written directly rather than waited for. The alternative is a test that
 * sleeps for twenty seconds to watch a boundary it can simply place itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderCancelWindowFlowTest {

    private static final String ADMIN_HOST = "admin.localhost";
    private static final String SETTINGS_URL = "/admin/orders/settings";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantDao tenantDao;
    @Autowired private OutletDao outletDao;
    @Autowired private UserDao userDao;
    @Autowired private OrderDao orderDao;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformSettingsDao platformSettingsDao;
    @Autowired private OrderService orderService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User fullAdmin;
    private User techManager;
    private Tenant tenant;
    private Outlet outlet;
    private Order paidOrder;

    /** Restored afterwards: this row is platform-wide, so leaving it changed leaks. */
    private String originalWindow;

    @BeforeAll
    void seed() {
        originalWindow = platformSettingsDao.findAll().get(OrderSettings.SELF_CANCEL_WINDOW);

        String runId = UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantDao.save(Tenant.builder().name("Cancel Window College " + runId)
                .status(TenantStatus.ACTIVE).build());
        outlet = outletDao.save(Outlet.builder().tenantId(tenant.getId())
                .name("Cancel Window Canteen").active(true).build());

        fullAdmin = userDao.save(User.builder().name("Window Admin")
                .email("window-admin-" + runId + "@test.local").passwordHash(passwordEncoder.encode("x"))
                .role(Role.SUPER_ADMIN).activeRole(Role.SUPER_ADMIN).active(true).build());
        techManager = userDao.save(User.builder().name("Window Tech")
                .email("window-tech-" + runId + "@test.local").passwordHash(passwordEncoder.encode("x"))
                .role(Role.TECH_MANAGER).activeRole(Role.TECH_MANAGER).active(true).build());

        User student = userDao.save(User.builder().tenantId(tenant.getId()).name("Window Student")
                .email("window-student-" + runId + "@test.local").passwordHash(passwordEncoder.encode("x"))
                .role(Role.USER).activeRole(Role.USER).active(true).build());

        paidOrder = orderDao.createOrder(Order.builder()
                .tenantId(tenant.getId()).outletId(outlet.getId()).userId(student.getId())
                .tokenNo("WIN-" + runId).totalAmount(new BigDecimal("30.00"))
                .status(OrderStatus.PAID).items(List.of()).build());
    }

    @AfterAll
    void restoreTheWindow() {
        if (originalWindow == null) {
            jdbcTemplate.update("DELETE FROM platform_settings WHERE config_key = ?",
                    OrderSettings.SELF_CANCEL_WINDOW);
        } else {
            platformSettingsDao.upsert(OrderSettings.SELF_CANCEL_WINDOW, originalWindow);
        }
    }

    /** Places the order's payment a chosen number of seconds in the past, by the DB's own clock. */
    private void paidSecondsAgo(int seconds) {
        jdbcTemplate.update(
                "UPDATE orders SET paid_at = DATE_SUB(NOW(), INTERVAL ? SECOND) WHERE id = ?",
                seconds, paidOrder.getId());
    }

    /** Back to the state of an order whose student has not been shown the confirmation yet. */
    private void clearAnchor() {
        jdbcTemplate.update("UPDATE orders SET cancel_window_starts_at = NULL WHERE id = ?",
                paidOrder.getId());
    }

    private boolean kitchenCanSeeTheOrder(int window) {
        return orderDao.findKitchenQueue(tenant.getId(), outlet.getId(), window).stream()
                .anyMatch(o -> o.getId().equals(paidOrder.getId()));
    }

    // ---- who may set it ----

    /** Renders, and shows the window actually in force rather than a placeholder. */
    @Test
    void aFullAdminSeesTheWindowCurrentlyInForce() throws Exception {
        platformSettingsDao.upsert(OrderSettings.SELF_CANCEL_WINDOW, "37");

        mockMvc.perform(get(SETTINGS_URL).header("Host", ADMIN_HOST)
                        .with(user(new AppUserPrincipal(fullAdmin))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"37\"")));
    }

    /**
     * The order list admits a tech manager; the rule behind it does not. This window decides
     * when a student is automatically owed their money back, which is a full-admin decision.
     */
    @Test
    void aTechManagerCanReadTheOrderListButNotChangeTheWindow() throws Exception {
        mockMvc.perform(get("/admin/orders").header("Host", ADMIN_HOST)
                        .with(user(new AppUserPrincipal(techManager))))
                .andExpect(status().isOk());

        mockMvc.perform(get(SETTINGS_URL).header("Host", ADMIN_HOST)
                        .with(user(new AppUserPrincipal(techManager))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(SETTINGS_URL).header("Host", ADMIN_HOST).with(csrf())
                        .param("selfCancelWindowSeconds", "5")
                        .with(user(new AppUserPrincipal(techManager))))
                .andExpect(status().isForbidden());
    }

    // ---- what saving it does ----

    /** The point of the whole change: the typed number, not a constant, is what orders obey. */
    @Test
    void savingTheWindowChangesWhatTheOrderPathReads() throws Exception {
        mockMvc.perform(post(SETTINGS_URL).header("Host", ADMIN_HOST).with(csrf())
                        .param("selfCancelWindowSeconds", "45")
                        .with(user(new AppUserPrincipal(fullAdmin))))
                .andExpect(status().is3xxRedirection());

        assertThat(orderService.selfCancelWindowSeconds()).isEqualTo(45);
    }

    @Test
    void anOversizedWindowIsClampedRatherThanStored() throws Exception {
        mockMvc.perform(post(SETTINGS_URL).header("Host", ADMIN_HOST).with(csrf())
                        .param("selfCancelWindowSeconds", "99999")
                        .with(user(new AppUserPrincipal(fullAdmin))))
                .andExpect(status().is3xxRedirection());

        assertThat(orderService.selfCancelWindowSeconds())
                .isEqualTo(OrderSettings.MAX_SELF_CANCEL_WINDOW_SECONDS);
    }

    /** A mistyped number is a form error, not a 500, and must not disturb the live setting. */
    @Test
    void aNonNumericWindowIsRejectedAndLeavesTheLiveSettingAlone() throws Exception {
        platformSettingsDao.upsert(OrderSettings.SELF_CANCEL_WINDOW, "30");

        mockMvc.perform(post(SETTINGS_URL).header("Host", ADMIN_HOST).with(csrf())
                        .param("selfCancelWindowSeconds", "twenty")
                        .with(user(new AppUserPrincipal(fullAdmin))))
                .andExpect(status().is3xxRedirection());

        assertThat(orderService.selfCancelWindowSeconds()).isEqualTo(30);
    }

    // ---- what the window actually enforces, in SQL ----

    /**
     * The safety property, checked against MySQL's own clock on both sides of the boundary
     * and exactly on it. Cancellable and kitchen-visible must always be opposites: if they
     * ever agreed, an order would be either cookable while still refundable, or invisible
     * to everyone.
     */
    @Test
    void cancellableAndKitchenVisibleAreExactComplementsAcrossTheBoundary() {
        paidSecondsAgo(25);
        clearAnchor();

        // Window not yet reached at 30s: the student still owns it, the kitchen cannot see it.
        assertThat(orderDao.isWithinSelfCancelWindow(paidOrder.getId(), tenant.getId(), 30)).isTrue();
        assertThat(kitchenCanSeeTheOrder(30)).isFalse();

        // Window already passed at 20s: the kitchen owns it, the student is too late.
        assertThat(orderDao.isWithinSelfCancelWindow(paidOrder.getId(), tenant.getId(), 20)).isFalse();
        assertThat(kitchenCanSeeTheOrder(20)).isTrue();

        // Exactly on the boundary the kitchen wins, because the queue tests >= and the
        // cancel tests <. The order is handed over the instant the window elapses.
        assertThat(orderDao.isWithinSelfCancelWindow(paidOrder.getId(), tenant.getId(), 25)).isFalse();
        assertThat(kitchenCanSeeTheOrder(25)).isTrue();
    }

    // ---- where the window is measured from ----

    /**
     * The bug this was reported for. Razorpay's webhook marks the order paid while the
     * student is still on the payment sheet, so by the time their browser calls back, part
     * of the window is already spent. Starting it at the callback gives them the whole
     * window from the moment they are actually shown the confirmation.
     */
    @Test
    void theWindowRunsFromTheConfirmationTheStudentSawNotFromCapture() {
        paidSecondsAgo(12);
        clearAnchor();

        // Measured from capture, most of the window is already gone.
        assertThat(orderDao.selfCancelSecondsLeft(paidOrder.getId(), tenant.getId(), 20))
                .isBetween(7, 8);

        assertThat(orderDao.startCancelWindow(paidOrder.getId(), tenant.getId(), 20)).isTrue();

        // Measured from the confirmation, they have all of it.
        assertThat(orderDao.selfCancelSecondsLeft(paidOrder.getId(), tenant.getId(), 20))
                .isBetween(19, 20);
        assertThat(kitchenCanSeeTheOrder(20)).isFalse();
    }

    /** A repeated callback must not become a way to keep the kitchen waiting indefinitely. */
    @Test
    void startingTheWindowTwiceDoesNothingTheSecondTime() {
        paidSecondsAgo(2);
        clearAnchor();

        assertThat(orderDao.startCancelWindow(paidOrder.getId(), tenant.getId(), 20)).isTrue();
        assertThat(orderDao.startCancelWindow(paidOrder.getId(), tenant.getId(), 20)).isFalse();
    }

    /**
     * The invariant that keeps the counter sane: an order the kitchen has already been shown
     * can never disappear off the queue again. A student whose device comes back long after
     * the window elapsed is simply too late.
     */
    @Test
    void anOrderTheKitchenAlreadyHasIsNeverPulledBack() {
        paidSecondsAgo(90);
        clearAnchor();
        assertThat(kitchenCanSeeTheOrder(20)).isTrue();

        assertThat(orderDao.startCancelWindow(paidOrder.getId(), tenant.getId(), 20)).isFalse();

        assertThat(kitchenCanSeeTheOrder(20)).isTrue();
        assertThat(orderDao.isWithinSelfCancelWindow(paidOrder.getId(), tenant.getId(), 20)).isFalse();
    }

    /** A student who never comes back must not strand the order: it falls back to paid_at. */
    @Test
    void anOrderWhoseStudentNeverReturnsStillReachesTheKitchen() {
        paidSecondsAgo(30);
        clearAnchor();

        assertThat(orderDao.selfCancelSecondsLeft(paidOrder.getId(), tenant.getId(), 20)).isZero();
        assertThat(kitchenCanSeeTheOrder(20)).isTrue();
    }

    /** Zero is the off switch: nothing is ever cancellable, everything is immediately cookable. */
    @Test
    void aZeroWindowHandsEveryOrderStraightToTheKitchen() {
        paidSecondsAgo(0);
        clearAnchor();

        assertThat(orderDao.isWithinSelfCancelWindow(paidOrder.getId(), tenant.getId(), 0)).isFalse();
        assertThat(kitchenCanSeeTheOrder(0)).isTrue();
    }

    /** The countdown the student sees comes from the same clock, and never runs negative. */
    @Test
    void theCountdownReflectsTheConfiguredWindowAndFloorsAtZero() {
        paidSecondsAgo(5);
        clearAnchor();
        assertThat(orderDao.selfCancelSecondsLeft(paidOrder.getId(), tenant.getId(), 20))
                .isBetween(14, 15);

        paidSecondsAgo(90);
        clearAnchor();
        assertThat(orderDao.selfCancelSecondsLeft(paidOrder.getId(), tenant.getId(), 20)).isZero();
    }
}
