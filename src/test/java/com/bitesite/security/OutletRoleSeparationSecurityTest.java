package com.bitesite.security;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.dao.MenuItemDao;
import com.bitesite.dao.OutletDao;
import com.bitesite.dao.UserDao;
import com.bitesite.model.MenuItem;
import com.bitesite.model.Outlet;
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

import java.math.BigDecimal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Separation *within* the outlet portal — the class of test this codebase did not have.
 *
 * <p>{@link RolePermissionSecurityTest} proves a canteen account cannot reach the admin or
 * student consoles. It says nothing about what two accounts on the *same* portal may do to
 * each other's work, because until the CANTEEN_STAFF split there was only one outlet role
 * and nothing to separate. The URL rule in SecurityConfig still cannot express this
 * distinction — both roles are admitted to /canteen/** — so everything asserted here is
 * carried by PortalGuard, and if those per-method guards were dropped the app would still
 * start, still serve, and quietly let an operator delete the menu.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutletRoleSeparationSecurityTest {

    private static final String OUTLET_HOST = "outlet.localhost";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantDao tenantDao;
    @Autowired private OutletDao outletDao;
    @Autowired private UserDao userDao;
    @Autowired private MenuItemDao menuItemDao;
    @Autowired private PasswordEncoder passwordEncoder;

    private User manager;
    private User operator;
    private Long itemId;

    @BeforeAll
    void seedOneOutletWithBothRoles() {
        String runId = java.util.UUID.randomUUID().toString().substring(0, 8);
        Tenant tenant = tenantDao.save(Tenant.builder().name("Outlet Split College " + runId)
                .status(TenantStatus.ACTIVE).build());
        Outlet outlet = outletDao.save(Outlet.builder().tenantId(tenant.getId())
                .name("Split Canteen " + runId).active(true).build());

        manager = userDao.save(User.builder().tenantId(tenant.getId()).outletId(outlet.getId())
                .name("Manager").email("split-mgr-" + runId + "@test.local")
                .passwordHash(passwordEncoder.encode("x"))
                .role(Role.CANTEEN_MANAGER).activeRole(Role.CANTEEN_MANAGER).active(true).build());
        operator = userDao.save(User.builder().tenantId(tenant.getId()).outletId(outlet.getId())
                .name("Operator").email("split-op-" + runId + "@test.local")
                .passwordHash(passwordEncoder.encode("x"))
                .role(Role.CANTEEN_OPERATOR).activeRole(Role.CANTEEN_OPERATOR).active(true).build());

        // A real row, so the {id} routes exercise the guard rather than a 404.
        itemId = menuItemDao.save(MenuItem.builder().tenantId(tenant.getId()).outletId(outlet.getId())
                .name("Split Samosa").category("Snacks").price(new BigDecimal("20.00"))
                .available(true).build()).getId();
    }

    // ---- The operator must not be able to author the menu ----

    @Test
    void operatorCannotOpenTheNewItemForm() throws Exception {
        // Guarded on the GET too: showing a form that 403s on submit is worse than hiding it.
        mockMvc.perform(get("/canteen/menu/new").header("Host", OUTLET_HOST)
                        .with(user(new AppUserPrincipal(operator))))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorCannotCreateAMenuItem() throws Exception {
        mockMvc.perform(post("/canteen/menu").header("Host", OUTLET_HOST).with(csrf())
                        .param("name", "Sneaky").param("category", "Snacks").param("price", "10.00")
                        .with(user(new AppUserPrincipal(operator))))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorCannotEditPrices() throws Exception {
        mockMvc.perform(get("/canteen/menu/" + itemId + "/edit").header("Host", OUTLET_HOST)
                        .with(user(new AppUserPrincipal(operator))))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorCannotDeleteAMenuItem() throws Exception {
        // The one that matters most: before the split this was reachable by anyone who
        // could work the counter.
        mockMvc.perform(post("/canteen/menu/" + itemId + "/delete").header("Host", OUTLET_HOST)
                        .with(csrf()).with(user(new AppUserPrincipal(operator))))
                .andExpect(status().isForbidden());
    }

    // ---- The operator must still be able to run the counter ----

    @Test
    void operatorCanWorkTheQueue() throws Exception {
        mockMvc.perform(get("/canteen/queue").header("Host", OUTLET_HOST)
                        .with(user(new AppUserPrincipal(operator))))
                .andExpect(status().isOk());
    }

    @Test
    void operatorCanPollTheLiveQueueApi() throws Exception {
        // The queue page's twin. If this drifts from the page's scope the queue silently
        // stops refreshing for whichever role the API forgot.
        mockMvc.perform(get("/api/orders/queue").header("Host", OUTLET_HOST)
                        .with(user(new AppUserPrincipal(operator))))
                .andExpect(status().isOk());
    }

    @Test
    void operatorCanSeeTheMenuToReachTheStockToggles() throws Exception {
        mockMvc.perform(get("/canteen/menu").header("Host", OUTLET_HOST)
                        .with(user(new AppUserPrincipal(operator))))
                .andExpect(status().isOk());
    }

    @Test
    void operatorCanMarkAnItemOutOfStock() throws Exception {
        // Deliberate: an operator can already pause the whole outlet, so per-item
        // availability is a gentler version of a power they hold. Pinned so the decision
        // is not silently reversed.
        mockMvc.perform(post("/canteen/menu/" + itemId + "/toggle").header("Host", OUTLET_HOST)
                        .with(csrf()).with(user(new AppUserPrincipal(operator))))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void operatorCanPauseNewOrders() throws Exception {
        mockMvc.perform(post("/canteen/queue/accepting").header("Host", OUTLET_HOST)
                        .with(csrf()).param("accepting", "false")
                        .with(user(new AppUserPrincipal(operator))))
                .andExpect(status().is3xxRedirection());
    }

    // ---- The manager is a superset, not a different set ----

    @Test
    void managerCanAuthorTheMenu() throws Exception {
        mockMvc.perform(get("/canteen/menu/new").header("Host", OUTLET_HOST)
                        .with(user(new AppUserPrincipal(manager))))
                .andExpect(status().isOk());
    }

    @Test
    void managerCanAlsoWorkTheQueue() throws Exception {
        // A manager alone on shift still has to run the counter.
        mockMvc.perform(get("/canteen/queue").header("Host", OUTLET_HOST)
                        .with(user(new AppUserPrincipal(manager))))
                .andExpect(status().isOk());
    }
}
