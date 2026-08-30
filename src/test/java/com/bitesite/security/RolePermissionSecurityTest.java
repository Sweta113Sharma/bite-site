package com.bitesite.security;

import com.bitesite.config.AppUserPrincipal;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves each console is actually locked to its own role at the HTTP layer — not just
 * "the menu doesn't show a link", but a direct request gets a 403. With multi-portal
 * routing, tests must set the Host header to the correct portal subdomain so the
 * {@code PortalGateFilter} allows the request through.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RolePermissionSecurityTest {

    // Portal host headers for test routing
    private static final String APP_HOST = "app.localhost";
    private static final String OUTLET_HOST = "outlet.localhost";
    private static final String ADMIN_HOST = "admin.localhost";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantDao tenantDao;
    @Autowired private UserDao userDao;
    @Autowired private PasswordEncoder passwordEncoder;

    private User student;
    private User canteenStaff;
    private User superAdmin;
    private User techManager;

    @BeforeAll
    void seedOneUserPerRole() {
        String runId = java.util.UUID.randomUUID().toString().substring(0, 8);
        Tenant tenant = tenantDao.save(Tenant.builder().name("Permission Test College " + runId)
                .status(TenantStatus.ACTIVE).build());

        student = userDao.save(User.builder().tenantId(tenant.getId()).name("Student")
                .email("perm-student-" + runId + "@test.local").passwordHash(passwordEncoder.encode("x"))
                .role(Role.USER).activeRole(Role.USER).active(true).build());
        canteenStaff = userDao.save(User.builder().tenantId(tenant.getId()).name("Canteen")
                .email("perm-canteen-" + runId + "@test.local").passwordHash(passwordEncoder.encode("x"))
                .role(Role.CANTEEN_MANAGER).activeRole(Role.CANTEEN_MANAGER).active(true).build());
        superAdmin = userDao.save(User.builder().name("Admin")
                .email("perm-admin-" + runId + "@test.local").passwordHash(passwordEncoder.encode("x"))
                .role(Role.SUPER_ADMIN).activeRole(Role.SUPER_ADMIN).active(true).build());
        techManager = userDao.save(User.builder().name("Tech")
                .email("perm-tech-" + runId + "@test.local").passwordHash(passwordEncoder.encode("x"))
                .role(Role.TECH_MANAGER).activeRole(Role.TECH_MANAGER).active(true).build());
    }

    // ---- APP portal (app.localhost) ----

    @Test
    void studentCanReachTheirOwnConsole() throws Exception {
        mockMvc.perform(get("/student/menu").header("Host", APP_HOST)
                        .with(user(new AppUserPrincipal(student))))
                .andExpect(status().isOk());
    }

    @Test
    void studentCannotReachTheAdminConsole() throws Exception {
        mockMvc.perform(get("/admin/tenants").header("Host", ADMIN_HOST)
                        .with(user(new AppUserPrincipal(student))))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentCannotReachTheCanteenConsole() throws Exception {
        mockMvc.perform(get("/canteen/queue").header("Host", OUTLET_HOST)
                        .with(user(new AppUserPrincipal(student))))
                .andExpect(status().isForbidden());
    }

    // ---- OUTLET portal (outlet.localhost) ----

    @Test
    void canteenStaffCannotReachTheAdminConsole() throws Exception {
        mockMvc.perform(get("/admin/tenants").header("Host", ADMIN_HOST)
                        .with(user(new AppUserPrincipal(canteenStaff))))
                .andExpect(status().isForbidden());
    }

    @Test
    void canteenStaffCannotReachTheStudentConsole() throws Exception {
        mockMvc.perform(get("/student/menu").header("Host", APP_HOST)
                        .with(user(new AppUserPrincipal(canteenStaff))))
                .andExpect(status().isForbidden());
    }

    // ---- ADMIN portal (admin.localhost) ----

    @Test
    void superAdminCanReachTheAdminConsole() throws Exception {
        mockMvc.perform(get("/admin/tenants").header("Host", ADMIN_HOST)
                        .with(user(new AppUserPrincipal(superAdmin))))
                .andExpect(status().isOk());
    }

    @Test
    void superAdminCanReachTheTechManagerConsole() throws Exception {
        mockMvc.perform(get("/techmgr").header("Host", ADMIN_HOST)
                        .with(user(new AppUserPrincipal(superAdmin))))
                .andExpect(status().isOk());
    }

    @Test
    void techManagerCanReachTheAdminPortal() throws Exception {
        // TECH_MANAGER can access admin portal routes within OPS_SCOPE (e.g. grievances)
        // but NOT FULL_ADMIN routes like /admin/tenants
        mockMvc.perform(get("/admin/grievances").header("Host", ADMIN_HOST)
                        .with(user(new AppUserPrincipal(techManager))))
                .andExpect(status().isOk());
    }

    // ---- Portal gate: wrong portal → 403 ----

    @Test
    void superAdminCannotReachStudentConsoleOnAppPortal() throws Exception {
        mockMvc.perform(get("/student/menu").header("Host", APP_HOST)
                        .with(user(new AppUserPrincipal(superAdmin))))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousRequestsAreRedirectedToLoginNotGivenA403() throws Exception {
        mockMvc.perform(get("/student/menu").header("Host", APP_HOST))
                .andExpect(status().is3xxRedirection());
    }
}
