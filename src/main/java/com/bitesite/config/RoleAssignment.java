package com.bitesite.config;

import com.bitesite.model.Role;
import com.bitesite.model.User;
import org.springframework.security.access.AccessDeniedException;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who may hand out which role. One class, because this is the rule that decides who can
 * grant themselves power, and a rule like that must have exactly one version of itself.
 *
 * <p>The shape of it:
 *
 * <ul>
 *   <li>A <b>SUPER_ADMIN</b> may grant or revoke anything, on anyone.</li>
 *   <li>An <b>ADMIN</b> may grant or revoke every role that is not elevated — tech
 *       manager, canteen manager, canteen operator, student — on any account that does
 *       not itself hold an elevated role.</li>
 *   <li>Nobody else may change roles at all.</li>
 * </ul>
 *
 * <p><b>Why the second condition exists.</b> Blocking an ADMIN from granting SUPER_ADMIN
 * stops the obvious escalation. It does not stop the patient one: strip the super admin
 * of their other roles, or revoke the super admin outright, and the platform is left with
 * nobody who can appoint admins — which hands the ADMIN the run of it by subtraction. So
 * an account holding an elevated role is off limits to an ADMIN entirely, not just its
 * elevated grants.
 *
 * <p><b>Authorised on the grant, not the view-mode</b>, matching
 * {@link PortalGuard#requireSuperAdmin}. A super admin currently viewing as a tech
 * manager is still a super admin; switching view-mode is a convenience and must not be a
 * way to lose or gain authority.
 */
public final class RoleAssignment {

    private RoleAssignment() {}

    /** True if the actor may grant or revoke this particular role. */
    public static boolean canAssign(User actor, Role role) {
        if (actor == null || role == null) {
            return false;
        }
        if (actor.hasRole(Role.SUPER_ADMIN)) {
            return true;
        }
        return actor.hasRole(Role.ADMIN) && !role.isElevated();
    }

    /**
     * True if the actor may change this account's roles at all, before asking which role.
     * An ADMIN is refused any account that holds an elevated role — see the class note.
     */
    public static boolean canManage(User actor, User target) {
        if (actor == null || target == null) {
            return false;
        }
        if (actor.hasRole(Role.SUPER_ADMIN)) {
            return true;
        }
        if (!actor.hasRole(Role.ADMIN)) {
            return false;
        }
        return target.getRoles() == null || target.getRoles().stream().noneMatch(Role::isElevated);
    }

    /** The roles this actor may offer, for building a picker that cannot lie. */
    public static Set<Role> assignableBy(User actor) {
        return Arrays.stream(Role.values())
                .filter(role -> canAssign(actor, role))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * The gate every role change goes through.
     *
     * @throws AccessDeniedException if the actor may not touch this account, or may not
     *                               hand out this role
     */
    public static void requireCanAssign(User actor, User target, Role role) {
        if (!canManage(actor, target)) {
            throw new AccessDeniedException(
                    "Only a super admin can change the roles of an account that holds an elevated role.");
        }
        if (!canAssign(actor, role)) {
            throw new AccessDeniedException(
                    "Only a super admin can grant or revoke " + role + ".");
        }
    }

    /** The same gate for account creation, where there is no existing target yet. */
    public static void requireCanCreateWith(User actor, Role role) {
        if (!canAssign(actor, role)) {
            throw new AccessDeniedException(
                    "Only a super admin can create an account with the role " + role + ".");
        }
    }
}
