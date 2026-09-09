package com.bitesite.config;

import com.bitesite.model.Role;
import com.bitesite.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rule that decides who can hand out power. Every case here is one somebody would try.
 */
class RoleAssignmentTest {

    private static User with(Role active, Role... roles) {
        return User.builder()
                .id(1L).name("Someone").email("someone@test.local")
                .activeRole(active)
                .roles(roles.length == 0 ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(Set.of(roles)))
                .build();
    }

    private static final User SUPER = with(Role.SUPER_ADMIN, Role.SUPER_ADMIN);
    private static final User ADMIN = with(Role.ADMIN, Role.ADMIN);
    private static final User TECH = with(Role.TECH_MANAGER, Role.TECH_MANAGER);

    // ---- what a super admin may do ---------------------------------------

    @Test
    void superAdminCanAssignEveryRoleThatExists() {
        for (Role role : Role.values()) {
            assertThat(RoleAssignment.canAssign(SUPER, role))
                    .as("super admin assigning %s", role).isTrue();
        }
    }

    @Test
    void superAdminCanManageAnAccountThatHoldsAnElevatedRole() {
        assertThat(RoleAssignment.canManage(SUPER, ADMIN)).isTrue();
        assertThat(RoleAssignment.canManage(SUPER, with(Role.SUPER_ADMIN, Role.SUPER_ADMIN))).isTrue();
    }

    // ---- what an admin may do --------------------------------------------

    @Test
    void adminCanAssignTheOrdinaryRoles() {
        assertThat(RoleAssignment.canAssign(ADMIN, Role.TECH_MANAGER)).isTrue();
        assertThat(RoleAssignment.canAssign(ADMIN, Role.CANTEEN_MANAGER)).isTrue();
        assertThat(RoleAssignment.canAssign(ADMIN, Role.CANTEEN_OPERATOR)).isTrue();
        assertThat(RoleAssignment.canAssign(ADMIN, Role.USER)).isTrue();
    }

    @Test
    void adminCannotMakeAnybodyASuperAdmin() {
        assertThat(RoleAssignment.canAssign(ADMIN, Role.SUPER_ADMIN)).isFalse();
    }

    /** Including themselves, and including another admin — an admin cannot mint a peer. */
    @Test
    void adminCannotGrantTheAdminRoleEither() {
        assertThat(RoleAssignment.canAssign(ADMIN, Role.ADMIN)).isFalse();
    }

    @Test
    void adminCanManageAnOrdinaryAccount() {
        assertThat(RoleAssignment.canManage(ADMIN, TECH)).isTrue();
        assertThat(RoleAssignment.canManage(ADMIN, with(Role.USER, Role.USER))).isTrue();
    }

    /**
     * The escalation by subtraction: an admin who cannot promote themselves could instead
     * demote everyone above them, and end up in charge of a platform with no super admin.
     */
    @Test
    void adminCannotTouchAnAccountHoldingAnElevatedRole() {
        assertThat(RoleAssignment.canManage(ADMIN, SUPER)).isFalse();
        assertThat(RoleAssignment.canManage(ADMIN, ADMIN)).isFalse();
        // Even to remove a perfectly ordinary role that account also happens to hold.
        User superWhoIsAlsoTech = with(Role.SUPER_ADMIN, Role.SUPER_ADMIN, Role.TECH_MANAGER);
        assertThat(RoleAssignment.canManage(ADMIN, superWhoIsAlsoTech)).isFalse();
    }

    // ---- everybody else ---------------------------------------------------

    @Test
    void aTechManagerCannotAssignAnything() {
        for (Role role : Role.values()) {
            assertThat(RoleAssignment.canAssign(TECH, role))
                    .as("tech manager assigning %s", role).isFalse();
        }
        assertThat(RoleAssignment.canManage(TECH, with(Role.USER, Role.USER))).isFalse();
    }

    @Test
    void nullsAreRefusedRatherThanTreatedAsPermission() {
        assertThat(RoleAssignment.canAssign(null, Role.USER)).isFalse();
        assertThat(RoleAssignment.canAssign(SUPER, null)).isFalse();
        assertThat(RoleAssignment.canManage(null, TECH)).isFalse();
        assertThat(RoleAssignment.canManage(ADMIN, null)).isFalse();
    }

    // ---- authority comes from the grant, not the view-mode ----------------

    /** A super admin looking at the console as a tech manager is still a super admin. */
    @Test
    void authorityFollowsTheGrantNotTheCurrentViewMode() {
        User superViewingAsTech = with(Role.TECH_MANAGER, Role.SUPER_ADMIN, Role.TECH_MANAGER);
        assertThat(RoleAssignment.canAssign(superViewingAsTech, Role.SUPER_ADMIN)).isTrue();
        assertThat(RoleAssignment.canManage(superViewingAsTech, ADMIN)).isTrue();
    }

    /** And the reverse: an admin does not gain anything by switching view-mode. */
    @Test
    void anAdminViewingAsSomethingElseGainsNothing() {
        User adminViewingAsTech = with(Role.TECH_MANAGER, Role.ADMIN, Role.TECH_MANAGER);
        assertThat(RoleAssignment.canAssign(adminViewingAsTech, Role.SUPER_ADMIN)).isFalse();
    }

    // ---- the picker the screen builds -------------------------------------

    @Test
    void assignableByOffersAnAdminEverythingExceptTheElevatedRoles() {
        assertThat(RoleAssignment.assignableBy(ADMIN))
                .containsExactlyInAnyOrder(Role.TECH_MANAGER, Role.CANTEEN_MANAGER,
                        Role.CANTEEN_OPERATOR, Role.USER);
    }

    @Test
    void assignableByOffersASuperAdminEverything() {
        assertThat(RoleAssignment.assignableBy(SUPER)).containsExactlyInAnyOrder(Role.values());
    }

    @Test
    void assignableByOffersATechManagerNothing() {
        assertThat(RoleAssignment.assignableBy(TECH)).isEmpty();
    }

    // ---- the gate itself ---------------------------------------------------

    @Test
    void theGateRefusesAnAdminReachingForAnElevatedGrant() {
        assertThatThrownBy(() -> RoleAssignment.requireCanAssign(ADMIN, TECH, Role.SUPER_ADMIN))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SUPER_ADMIN");
    }

    @Test
    void theGateRefusesAnAdminReachingForAnElevatedAccount() {
        assertThatThrownBy(() -> RoleAssignment.requireCanAssign(ADMIN, SUPER, Role.TECH_MANAGER))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("elevated");
    }

    @Test
    void theGateLetsTheOrdinaryCaseThrough() {
        assertThatCode(() -> RoleAssignment.requireCanAssign(ADMIN, TECH, Role.CANTEEN_MANAGER))
                .doesNotThrowAnyException();
        assertThatCode(() -> RoleAssignment.requireCanAssign(SUPER, ADMIN, Role.SUPER_ADMIN))
                .doesNotThrowAnyException();
    }

    @Test
    void creatingAnElevatedAccountIsRefusedToAnAdmin() {
        assertThatThrownBy(() -> RoleAssignment.requireCanCreateWith(ADMIN, Role.ADMIN))
                .isInstanceOf(AccessDeniedException.class);
        assertThatCode(() -> RoleAssignment.requireCanCreateWith(ADMIN, Role.TECH_MANAGER))
                .doesNotThrowAnyException();
    }

    /** If a role is ever added, this says out loud which side of the line it landed on. */
    @Test
    void exactlyTwoRolesAreElevated() {
        assertThat(java.util.Arrays.stream(Role.values()).filter(Role::isElevated).toList())
                .containsExactly(Role.SUPER_ADMIN, Role.ADMIN);
    }
}
