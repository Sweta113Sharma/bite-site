package com.bitesite.service;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.dao.UserDao;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock private UserDao userDao;

    private CustomUserDetailsService service;

    @BeforeEach
    void setUp() {
        service = new CustomUserDetailsService(userDao);
    }

    @Test
    void throwsUsernameNotFoundForAnUnknownEmail() {
        when(userDao.findByEmail("nobody@nowhere.local")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("nobody@nowhere.local"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void wrapsTheDomainUserInAnAppUserPrincipalWithTheRightAuthority() {
        User user = User.builder().id(1L).tenantId(2L).name("Demo Student").email("student@demo.local")
                .passwordHash("hash").role(Role.USER).activeRole(Role.USER)
                .roles(EnumSet.of(Role.USER)).active(true).build();
        when(userDao.findByEmail("student@demo.local")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("student@demo.local");

        assertThat(details).isInstanceOf(AppUserPrincipal.class);
        assertThat(details.getUsername()).isEqualTo("student@demo.local");
        assertThat(details.getPassword()).isEqualTo("hash");
        assertThat(details.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
        assertThat(((AppUserPrincipal) details).getUser().getTenantId()).isEqualTo(2L);
    }

    @Test
    void inactiveUserIsReportedAsDisabledSoSpringSecurityBlocksTheLogin() {
        User user = User.builder().id(1L).tenantId(2L).name("Deactivated").email("gone@demo.local")
                .passwordHash("hash").role(Role.USER).activeRole(Role.USER)
                .roles(EnumSet.of(Role.USER)).active(false).build();
        when(userDao.findByEmail("gone@demo.local")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("gone@demo.local");

        assertThat(details.isEnabled()).isFalse();
    }
}
