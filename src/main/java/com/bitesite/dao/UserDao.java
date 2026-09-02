package com.bitesite.dao;

import com.bitesite.model.Role;
import com.bitesite.model.User;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface UserDao {
    Optional<User> findById(Long id);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    List<User> findByTenantId(Long tenantId);

    /** Staff attached to one outlet. Tenant-scoped as well as outlet-scoped so an outlet
     * id from another college cannot be used to read its staff list. */
    List<User> findByOutletId(Long outletId, Long tenantId);

    List<User> findPlatformUsers();

    User save(User user);

    void setActive(Long id, boolean active);

    /** Per-category notification preferences. Server-side, so they hold across devices —
     * the old push toggle read its state from the browser and desynced. */
    void updateNotificationPreferences(Long id, boolean orderUpdates, boolean marketing);

    /** Clears a staff account's outlet assignment and switches the account off, used when
     * the outlet they belonged to is deleted. The row survives so their audit-log entries
     * still resolve to a person. */
    void detachFromOutlet(Long id);

    void markEmailVerified(Long id);

    void markPhoneVerified(Long id);

    /** Right-to-erasure support: scrubs PII (name, phone, roll number) and replaces the
     * email/password hash with unusable placeholders, but leaves the row itself in place
     * so order/payment/grievance history the canteen legitimately needs to keep still
     * has somewhere to point. */
    void anonymize(Long id, String anonymizedEmail, String anonymizedPasswordHash);

    // ---- Multi-role support ----

    /** Load all roles for a user from the user_roles table. */
    Set<Role> findRoles(Long userId);

    /** Grant a role to a user. No-op if they already hold it. */
    void grantRole(Long userId, Role role, Long actorUserId);

    /** Revoke a role from a user. No-op if they don't hold it. */
    void revokeRole(Long userId, Role role, Long actorUserId);

    /** Switch the user's active_role. Caller must validate it's in their entitlements. */
    void updateActiveRole(Long userId, Role activeRole);
}
