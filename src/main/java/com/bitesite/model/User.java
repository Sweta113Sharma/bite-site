package com.bitesite.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

// Serializable because this ends up inside the Spring Security SecurityContext, which
// Spring Session JDBC persists as a serialized blob per session row — not just an
// in-memory nicety, login itself throws NotSerializableException without this.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User implements Serializable {
    private Long id;
    private Long tenantId;
    private Long outletId;
    private String name;
    private String email;
    private String passwordHash;
    private String phone;
    private String rollNo;

    /** Primary/default role — kept for backward compatibility with existing code. */
    private Role role;

    /** The role the user is currently operating as (the "view-mode"). */
    private Role activeRole;

    /**
     * All roles this user is entitled to (loaded from user_roles table).
     * This is the source of truth for what portals/features they can access.
     * {@link #activeRole} must always be a member of this set.
     */
    @Builder.Default
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    private boolean active;

    // Fail-closed by default (Lombok's default for an unset boolean): a User built without
    // explicitly verifying email is treated as unverified, since email is required for
    // every account. phoneVerified defaults the other way — true — because phone
    // verification only ever applies to a self-registered student who both supplied a
    // phone number and has SMS actually configured; every other account (staff/admin,
    // students with no phone, or any account built without thinking about this field at
    // all) has nothing to verify, matching the DB column's own DEFAULT TRUE.
    private boolean emailVerified;
    @Builder.Default
    private boolean phoneVerified = true;

    private LocalDateTime createdAt;

    /** Whether this user holds the given role in their entitlements. */
    public boolean hasRole(Role r) {
        return roles != null && roles.contains(r);
    }

    /** Whether this user's active role is allowed on the given portal. */
    public boolean canAccessPortal(PortalTarget portal) {
        return activeRole != null && portal.allowsRole(activeRole);
    }
}
