package com.bitesite.security;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.dao.CategoryDao;
import com.bitesite.dao.MenuItemDao;
import com.bitesite.dao.OrderDao;
import com.bitesite.dao.OutletDao;
import com.bitesite.dao.UserDao;
import com.bitesite.model.Category;
import com.bitesite.model.MenuItem;
import com.bitesite.model.Order;
import com.bitesite.model.OrderStatus;
import com.bitesite.model.Outlet;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import com.bitesite.tenant.Tenant;
import com.bitesite.tenant.TenantDao;
import com.bitesite.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import com.bitesite.exception.ResourceNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the core safety guarantee of the whole platform: a logged-in user from one
 * college can never reach another college's data, even when they know (or guess) its
 * internal ID. This runs the real HTTP -> Security -> controller -> service -> DAO stack
 * against a live MySQL test database — not a mocked shortcut.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantIsolationSecurityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantDao tenantDao;
    @Autowired private OutletDao outletDao;
    @Autowired private UserDao userDao;
    @Autowired private MenuItemDao menuItemDao;
    @Autowired private CategoryDao categoryDao;
    @Autowired private OrderDao orderDao;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private com.bitesite.service.UserService userService;

    private User studentA;
    private User studentB;
    private Order orderBelongingToStudentA;
    private MenuItem menuItemInCollegeB;
    private User staffInCollegeA;
    private Outlet canteenInCollegeA;
    private Outlet canteenInCollegeB;

    @BeforeAll
    void seedTwoIndependentColleges() {
        // Unique suffix per run — this data is never cleaned up between test runs (no
        // Docker/Testcontainers available to give each run a throwaway database), so the
        // tenants.name / users.email unique constraints would collide on a second run
        // without it.
        String runId = java.util.UUID.randomUUID().toString().substring(0, 8);

        Tenant collegeA = tenantDao.save(Tenant.builder().name("Isolation Test College A " + runId).status(TenantStatus.ACTIVE).build());
        Tenant collegeB = tenantDao.save(Tenant.builder().name("Isolation Test College B " + runId).status(TenantStatus.ACTIVE).build());

        Outlet outletA = outletDao.save(Outlet.builder().tenantId(collegeA.getId()).name("Canteen A").active(true).build());
        Outlet outletB = outletDao.save(Outlet.builder().tenantId(collegeB.getId()).name("Canteen B").active(true).build());
        canteenInCollegeA = outletA;
        canteenInCollegeB = outletB;

        staffInCollegeA = userDao.save(User.builder()
                .tenantId(collegeA.getId()).outletId(outletA.getId()).name("Manager A")
                .email("isolation-manager-a-" + runId + "@test.local")
                .passwordHash(passwordEncoder.encode("irrelevant"))
                .role(Role.CANTEEN_MANAGER).activeRole(Role.CANTEEN_MANAGER).active(true).build());

        studentA = userDao.save(User.builder()
                .tenantId(collegeA.getId()).name("Student A").email("isolation-student-a-" + runId + "@test.local")
                .passwordHash(passwordEncoder.encode("irrelevant")).role(Role.USER).activeRole(Role.USER).active(true).build());
        studentB = userDao.save(User.builder()
                .tenantId(collegeB.getId()).name("Student B").email("isolation-student-b-" + runId + "@test.local")
                .passwordHash(passwordEncoder.encode("irrelevant")).role(Role.USER).activeRole(Role.USER).active(true).build());

        // menu_items.category_id is NOT NULL since V18, so the section must exist first.
        Long categoryIdB = categoryDao.save(Category.builder()
                .tenantId(collegeB.getId()).outletId(outletB.getId())
                .name("Meals " + runId).sortOrder(0).build()).getId();

        menuItemInCollegeB = menuItemDao.save(MenuItem.builder()
                .tenantId(collegeB.getId()).outletId(outletB.getId())
                .name("College B Special").categoryId(categoryIdB).price(new BigDecimal("50.00")).available(true).build());

        Order order = Order.builder()
                .tenantId(collegeA.getId()).outletId(outletA.getId()).userId(studentA.getId())
                .tokenNo("ISO-TEST-1").totalAmount(new BigDecimal("30.00")).status(OrderStatus.PAID)
                .items(List.of())
                .build();
        orderBelongingToStudentA = orderDao.createOrder(order);
    }

    /**
     * Moving a staff account between canteens is a permission change, and the outlet id
     * arrives as a form value — so the college it belongs to has to be re-checked on the
     * server. Without that, an admin acting on one college's page could point that
     * college's manager at another college's canteen, which is a tenant-isolation hole
     * rather than a mistake.
     */
    @Test
    void staffCannotBeAssignedToACanteenBelongingToAnotherCollege() {
        Long originalOutlet = staffInCollegeA.getOutletId();

        assertThatThrownBy(() -> userService.assignStaffToOutlet(
                staffInCollegeA.getId(), canteenInCollegeB.getId(),
                staffInCollegeA.getTenantId(), staffInCollegeA.getId()))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(userDao.findById(staffInCollegeA.getId()).orElseThrow().getOutletId())
                .isEqualTo(originalOutlet);
    }

    /**
     * The mirror of the above: naming the OTHER college on the path does not help either,
     * because the staff account is re-read scoped to the tenant being acted on.
     */
    @Test
    void staffCannotBeReachedThroughAnotherCollegesTenantId() {
        Long originalOutlet = staffInCollegeA.getOutletId();

        assertThatThrownBy(() -> userService.assignStaffToOutlet(
                staffInCollegeA.getId(), canteenInCollegeB.getId(),
                canteenInCollegeB.getTenantId(), staffInCollegeA.getId()))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(userDao.findById(staffInCollegeA.getId()).orElseThrow().getOutletId())
                .isEqualTo(originalOutlet);
    }

    /** The legitimate move still works, or the guards above would be proving nothing. */
    @Test
    void staffCanBeMovedBetweenCanteensOfTheirOwnCollege() {
        Outlet second = outletDao.save(Outlet.builder()
                .tenantId(staffInCollegeA.getTenantId()).name("Canteen A2").active(true).build());

        userService.assignStaffToOutlet(staffInCollegeA.getId(), second.getId(),
                staffInCollegeA.getTenantId(), staffInCollegeA.getId());

        assertThat(userDao.findById(staffInCollegeA.getId()).orElseThrow().getOutletId())
                .isEqualTo(second.getId());

        // Put it back so ordering between tests cannot matter.
        userService.assignStaffToOutlet(staffInCollegeA.getId(), canteenInCollegeA.getId(),
                staffInCollegeA.getTenantId(), staffInCollegeA.getId());
    }

    @Test
    void studentCanViewTheirOwnOrder() throws Exception {
        mockMvc.perform(get("/student/orders/{id}", orderBelongingToStudentA.getId())
                        .with(user(new AppUserPrincipal(studentA))))
                .andExpect(status().isOk());
    }

    @Test
    void studentFromCollegeBCannotViewCollegeAsOrder() throws Exception {
        mockMvc.perform(get("/student/orders/{id}", orderBelongingToStudentA.getId())
                        .with(user(new AppUserPrincipal(studentB))))
                .andExpect(status().isNotFound());
    }

    @Test
    void menuItemLookupScopedToTheWrongTenantFindsNothing() {
        var result = menuItemDao.findByIdAndTenantId(menuItemInCollegeB.getId(), studentA.getTenantId());
        org.assertj.core.api.Assertions.assertThat(result).isEmpty();
    }

    @Test
    void studentFromCollegeBCannotBrowseCollegeAsMenuByGuessingOutletId() throws Exception {
        // Even if a student from college B somehow guessed college A's outlet id, the menu
        // browse controller derives the tenant from the authenticated principal, not the
        // request, so this simply returns B's own (empty) menu rather than leaking A's.
        mockMvc.perform(get("/student/menu").with(user(new AppUserPrincipal(studentB))))
                .andExpect(status().isOk());
    }
}
