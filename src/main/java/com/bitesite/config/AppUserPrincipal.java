package com.bitesite.config;

import com.bitesite.model.Role;
import com.bitesite.model.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Spring Security adapter over the domain {@link User}. Carries the raw user so
 * controllers can pull {@code tenantId}/{@code outletId}/{@code id} straight off the
 * authenticated principal instead of re-querying, while {@link TenantContext} (resolved
 * from the subdomain, independently of who is logged in) remains the source of truth for
 * which tenant the current request is scoped to.
 *
 * <p>With multi-role support, {@link #getAuthorities()} exposes only the user's
 * {@code activeRole} — the "view-mode" — not their full entitlement set. This is
 * deliberate: Spring Security's URL-level {@code hasRole()} rules should gate on what
 * the user is <em>currently acting as</em>, the same way the portal gate does, otherwise
 * a user who holds roles on two different portals could reach the other portal's routes
 * just by knowing the URL, from whichever subdomain they're currently on. Checks that
 * genuinely need to key off the durable grant instead of the view-mode (e.g. "is this
 * person a super admin at all") go through {@code user.hasRole()} / {@code PortalGuard},
 * which read {@code user.getRoles()} directly — independent of what Spring Security sees.
 */
@Getter
public class AppUserPrincipal implements UserDetails {

    private final User user;

    public AppUserPrincipal(User user) {
        this.user = user;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Role authorityRole = user.getActiveRole() != null ? user.getActiveRole() : user.getRole();
        return List.of(new SimpleGrantedAuthority("ROLE_" + authorityRole.name()));
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    // Repurposed for "verification not finished yet" (distinct from isEnabled(), which
    // tracks admin deactivation) so the login-failure handler can tell the two apart and
    // give the user a specific, actionable message instead of a generic "bad credentials".
    // Both channels must clear — a student who supplied a phone number can't skip its OTP
    // by only checking email.
    @Override
    public boolean isAccountNonLocked() {
        return user.isEmailVerified() && user.isPhoneVerified();
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return user.isActive();
    }
}
